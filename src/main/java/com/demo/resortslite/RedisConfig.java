package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for Azure Cache for Redis integration.
 *
 * <p>Provides a {@link RedisTemplate} bean configured with JSON serialisation
 * for use by {@link BookingController} to store distributed session state
 * (cr-java-0065) and booking cache entries with TTL (cr-java-0067).
 */
@Configuration
public class RedisConfig {

    /**
     * Configures a {@link RedisTemplate} with String keys and JSON-serialised values.
     * This template is used by {@link BookingController} to replace in-memory
     * {@code HttpSession} and {@code HashMap} caching with Azure Cache for Redis.
     *
     * @param connectionFactory the Redis connection factory auto-configured by Spring Boot
     * @return the configured {@link RedisTemplate}
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
