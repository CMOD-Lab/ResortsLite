package com.demo.resortslite;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * RedisConfig configures Amazon ElastiCache for Redis as the backing store for:
 * <ul>
 *   <li>Spring Session — distributed HTTP session management replacing in-process
 *       HttpSession storage (cr-java-0065 / blockers 13–17).</li>
 *   <li>Spring Cache — distributed caching with TTL replacing the unbounded
 *       in-memory HashMap (cr-java-0067 / blocker-20).</li>
 * </ul>
 *
 * <p>Redis connection details (host, port, password) are injected from environment
 * variables REDIS_HOST, REDIS_PORT, and REDIS_PASSWORD via application.properties.
 */
@Configuration
@EnableCaching
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisConfig {

    /**
     * Configures a RedisTemplate with JSON serialization for storing booking objects.
     * Used by {@link BookingController} for distributed caching with TTL.
     *
     * @param connectionFactory the Redis connection factory (auto-configured by Spring Boot)
     * @return configured RedisTemplate instance
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
