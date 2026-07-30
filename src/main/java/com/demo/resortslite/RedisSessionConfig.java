package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Cloud-native session and cache configuration.
 *
 * Blocker-13,14,15,16,17 (cr-java-0065): Enables Spring Session backed by
 * Amazon ElastiCache for Redis, replacing in-memory HttpSession storage.
 * All session data is stored in Redis, enabling stateless application instances
 * that can scale horizontally behind an AWS ALB.
 *
 * Blocker-20 (cr-java-0067): RedisTemplate with TTL support replaces the
 * unbounded in-memory HashMap cache, ensuring controlled expiration and
 * consistent data across all instances.
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 3600)
public class RedisSessionConfig {

    /**
     * Configures a RedisTemplate with JSON serialization for storing
     * booking cache entries and session data in Amazon ElastiCache for Redis.
     *
     * @param connectionFactory the Redis connection factory (auto-configured by Spring Boot)
     * @return configured RedisTemplate
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
