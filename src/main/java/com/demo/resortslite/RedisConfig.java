package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * RedisConfig configures Spring Session with Amazon ElastiCache for Redis.
 *
 * Replaces HTTP session state (HttpSession) and in-memory HashMap caching
 * with a distributed, TTL-aware Redis store — enabling stateless, horizontally
 * scalable application instances behind an AWS ALB.
 *
 * Addresses:
 * - Blockers 13-17 (cr-java-0065): HTTP session state → Redis distributed session
 * - Blocker-20 (cr-java-0067): In-memory cache without TTL → Redis with TTL
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisConfig {

    /**
     * RedisTemplate configured with JSON serialisation for storing booking objects
     * and session attributes in Amazon ElastiCache for Redis.
     *
     * @param connectionFactory Spring-managed Redis connection factory
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
