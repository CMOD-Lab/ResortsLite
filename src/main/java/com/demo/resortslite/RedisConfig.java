package com.demo.resortslite;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisConfig — configures the {@link RedisTemplate} used for distributed
 * caching with TTL via Amazon ElastiCache for Redis.
 *
 * <p>This bean replaces the unbounded in-memory {@code HashMap} cache in
 * {@link BookingController} (blocker 20) and supports the Spring Session
 * Redis integration that replaces in-process HTTP session storage
 * (blockers 13–17).</p>
 */
@Configuration
public class RedisConfig {

    /**
     * Provides a {@link RedisTemplate} with String keys and JSON-serialized
     * values. Using JSON serialization ensures that cached objects survive
     * Redis restarts and are readable by any application instance.
     *
     * @param connectionFactory auto-configured by Spring Boot from
     *                          {@code spring.redis.host} / {@code spring.redis.port}
     * @return configured RedisTemplate
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Use Jackson JSON serializer for values (includes type information for deserialization)
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(Object.class);
        jsonSerializer.setObjectMapper(objectMapper);

        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
