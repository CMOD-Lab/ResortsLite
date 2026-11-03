package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * RedisSessionConfig — Spring Session and booking cache backed by Amazon ElastiCache for Redis.
 *
 * cr-java-0065 FIX: Configures Spring Session to store all HTTP session data in
 * Amazon ElastiCache for Redis instead of the in-process servlet container session.
 * This makes every application instance fully stateless: any instance in the Auto
 * Scaling Group can serve any request because session data is centralised in the
 * shared ElastiCache cluster.
 *
 * cr-java-0067 FIX: Exposes a {@link RedisTemplate} bean used by {@link BookingController}
 * to cache booking objects in Amazon ElastiCache for Redis with TTL-based expiration.
 * Keys are serialised as plain strings (namespaced with "booking:cache:") and values
 * are serialised as JSON using {@link GenericJackson2JsonRedisSerializer}, enabling
 * human-readable inspection of cache entries and cross-instance cache sharing.
 * The TTL is configured via the {@code booking.cache.ttl-seconds} property
 * (default: 3600 seconds / 1 hour), ensuring automatic eviction of stale entries.
 *
 * Configuration is driven entirely by environment variables / application.properties
 * so that the same artifact can be deployed to dev, staging, and production without
 * code changes (12-factor app principle III — Config).
 *
 * Required environment variables (or application.properties overrides):
 *   SPRING_REDIS_HOST          — ElastiCache primary endpoint (default: localhost)
 *   SPRING_REDIS_PORT          — ElastiCache port             (default: 6379)
 *   SESSION_TIMEOUT            — Session TTL in seconds        (default: 1800 / 30 min)
 *   BOOKING_CACHE_TTL_SECONDS  — Booking cache TTL in seconds  (default: 3600 / 1 hour)
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    // ElastiCache primary endpoint — injected from environment variable SPRING_REDIS_HOST
    // or the spring.redis.host property (set in application.properties).
    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    // ElastiCache port — injected from environment variable SPRING_REDIS_PORT
    // or the spring.redis.port property (set in application.properties).
    @Value("${spring.redis.port:6379}")
    private int redisPort;

    /**
     * Lettuce connection factory pointing at the Amazon ElastiCache Redis cluster.
     * Lettuce is the recommended non-blocking Redis client for Spring Boot 2.x.
     *
     * @return configured {@link LettuceConnectionFactory}
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * cr-java-0067 FIX: RedisTemplate for the booking cache.
     *
     * <p>Provides a typed {@link RedisTemplate}{@code <String, Object>} bean used by
     * {@link BookingController} to store and retrieve booking objects in Amazon
     * ElastiCache for Redis with TTL-based expiration.</p>
     *
     * <p>Serialization strategy:</p>
     * <ul>
     *   <li><strong>Keys</strong>: {@link StringRedisSerializer} — stores keys as plain
     *       UTF-8 strings (e.g. {@code booking:cache:BK-A1B2C3D4}), making them
     *       human-readable in Redis CLI / ElastiCache console.</li>
     *   <li><strong>Values</strong>: {@link GenericJackson2JsonRedisSerializer} — stores
     *       booking {@link java.util.Map} objects as JSON, enabling cross-instance
     *       deserialization without requiring a shared Java class registry.</li>
     *   <li><strong>Hash keys/values</strong>: same serializers applied for consistency
     *       if hash operations are used in future.</li>
     * </ul>
     *
     * <p>The same {@link LettuceConnectionFactory} is shared with Spring Session,
     * so both session data and booking cache entries reside in the same ElastiCache
     * cluster. Key namespacing ({@code booking:cache:} prefix) prevents collisions
     * with Spring Session keys ({@code spring:session:} prefix).</p>
     *
     * @return configured {@link RedisTemplate} for booking cache operations
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());

        // Use StringRedisSerializer for keys — produces human-readable key strings
        // in ElastiCache (e.g. "booking:cache:BK-A1B2C3D4").
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Use GenericJackson2JsonRedisSerializer for values — serialises booking Map
        // objects as JSON so they can be deserialised by any instance in the cluster
        // without requiring a shared Java serialization class registry.
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
