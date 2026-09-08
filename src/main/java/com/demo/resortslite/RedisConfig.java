package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for Azure Cache for Redis.
 *
 * Provides a RedisTemplate bean used by BookingController to store session state
 * and booking cache entries with TTL policies.
 *
 * Blocker-13/14/15/16/17 (cr-java-0065): Enables distributed session storage via Redis.
 * Blocker-20 (cr-java-0067): Enables distributed cache with TTL via Redis.
 *
 * Connection details (host, port, password, SSL) are configured in application.properties
 * and read from environment variables / Azure App Configuration at runtime.
 */
@Configuration
public class RedisConfig {

    /**
     * Configures a RedisTemplate with JSON serialization for storing complex objects.
     * String keys and JSON-serialized values allow cross-instance cache sharing.
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
