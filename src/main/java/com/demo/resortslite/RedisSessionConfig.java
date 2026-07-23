package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Azure Cache for Redis Configuration.
 *
 * cr-java-0065: Replaces in-memory HTTP session storage with Azure Cache for Redis
 * to enable stateless application architecture and horizontal scaling across
 * multiple instances.
 *
 * Spring Session automatically intercepts HttpSession operations and stores
 * session data in Redis, making it available to all application instances.
 *
 * Required environment variables / application properties:
 *   spring.redis.host=<AZURE_REDIS_HOST>          (e.g., myredis.redis.cache.windows.net)
 *   spring.redis.port=6380
 *   spring.redis.password=<AZURE_REDIS_PASSWORD>
 *   spring.redis.ssl=true
 *   spring.session.store-type=redis
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    /**
     * Configures a RedisTemplate with JSON serialization for storing
     * booking and session objects in Azure Cache for Redis.
     *
     * @param connectionFactory the Redis connection factory
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
