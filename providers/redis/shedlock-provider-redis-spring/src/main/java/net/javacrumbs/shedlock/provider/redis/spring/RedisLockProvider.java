/**
 * Copyright 2009-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.javacrumbs.shedlock.provider.redis.spring;

import net.javacrumbs.shedlock.core.AbstractSimpleLock;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.support.LockException;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static java.lang.Boolean.TRUE;
import static net.javacrumbs.shedlock.support.Utils.getHostname;
import static net.javacrumbs.shedlock.support.Utils.toIsoString;
import static org.springframework.data.redis.connection.RedisStringCommands.SetOption.SET_IF_ABSENT;

/**
 * Uses Redis's `SET resource-name anystring NX PX max-lock-ms-time` as locking mechanism.
 * See https://redis.io/commands/set
 */
public class RedisLockProvider implements LockProvider {
    private static final String KEY_PREFIX_DEFAULT = "job-lock";
    private static final String ENV_DEFAULT = "default";

    private final ShedlockRedisTemplate redisTemplate;
    private final String environment;
    private final String keyPrefix;

    public RedisLockProvider(@NotNull RedisConnectionFactory redisConn) {
        this(redisConn, ENV_DEFAULT);
    }

    /**
     * Creates RedisLockProvider
     *
     * @param redisConn   RedisConnectionFactory
     * @param environment environment is part of the key and thus makes sure there is not key conflict between
     *                    multiple ShedLock instances running on the same Redis
     */
    public RedisLockProvider(@NotNull RedisConnectionFactory redisConn, @NotNull String environment) {
        this(redisConn, environment, KEY_PREFIX_DEFAULT);
    }

    /**
     * Creates RedisLockProvider
     *
     * @param redisConn   RedisConnectionFactory
     * @param environment environment is part of the key and thus makes sure there is not key conflict between
     *                    multiple ShedLock instances running on the same Redis
     * @param keyPrefix   prefix of the key in Redis.
     */
    public RedisLockProvider(@NotNull RedisConnectionFactory redisConn, @NotNull String environment, @NotNull String keyPrefix) {
        this.redisTemplate = new ShedlockRedisTemplate(redisConn);
        this.environment = environment;
        this.keyPrefix = keyPrefix;
    }

    @Override
    @NotNull
    public Optional<SimpleLock> lock(@NotNull LockConfiguration lockConfiguration) {
        String key = buildKey(lockConfiguration.getName(), this.environment, this.keyPrefix);
        Expiration expiration = getExpiration(lockConfiguration.getLockAtMostUntil());
        if (TRUE.equals(redisTemplate.tryToSetExpiration(key, expiration, SET_IF_ABSENT))) {
            return Optional.of(new RedisLock(key, redisTemplate, lockConfiguration));
        } else {
            return Optional.empty();
        }
    }

    private static Expiration getExpiration(Instant until) {
        return Expiration.from(getMsUntil(until), TimeUnit.MILLISECONDS);
    }

    private static long getMsUntil(Instant until) {
        return Duration.between(Instant.now(), until).toMillis();
    }

    private static final class RedisLock extends AbstractSimpleLock {

        private final String key;
        private final ShedlockRedisTemplate redisTemplate;

        private RedisLock(String key, ShedlockRedisTemplate redisTemplate, LockConfiguration lockConfiguration) {
            super(lockConfiguration);
            this.key = key;
            this.redisTemplate = redisTemplate;
        }

        @Override
        public void doUnlock() {
            Expiration keepLockFor = getExpiration(lockConfiguration.getLockAtLeastUntil());
            // lock at least until is in the past
            if (keepLockFor.getExpirationTimeInMilliseconds() <= 0) {
                try {
                    redisTemplate.delete(key);
                } catch (Exception e) {
                    throw new LockException("Can not remove node", e);
                }
            } else {
                redisTemplate.tryToSetExpiration(key, keepLockFor, SetOption.SET_IF_PRESENT);
            }
        }
    }


    static String buildKey(String lockName, String env, String keyPrefix) {
        return String.format("%s:%s:%s", keyPrefix, env, lockName);
    }


    private static class ShedlockRedisTemplate extends StringRedisTemplate {
        private ShedlockRedisTemplate(RedisConnectionFactory connectionFactory) {
            super(connectionFactory);
        }

        private Boolean tryToSetExpiration(String key, Expiration expiration, SetOption option) {
            return execute(connection -> connection.set(serialize(key), buildValue(), expiration, option), false);
        }

        private byte[] buildValue() {
            return serialize(String.format("ADDED:%s@%s", toIsoString(Instant.now()), getHostname()));
        }

        private byte[] serialize(String string) {
            return getStringSerializer().serialize(string);
        }
    }
}
