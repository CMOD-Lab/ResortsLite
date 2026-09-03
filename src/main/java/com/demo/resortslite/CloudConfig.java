package com.demo.resortslite;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * CloudConfig — Spring configuration for cloud-native session and caching.
 *
 * Enables:
 *  - Spring Session backed by Amazon ElastiCache for Redis.
 *    Fixes blockers 13-17 (cr-java-0065): HTTP session state is now stored in Redis,
 *    enabling stateless application instances and horizontal scaling.
 *    maxInactiveIntervalInSeconds = 1800 (30 minutes).
 *
 *  - Spring Cache backed by Amazon ElastiCache for Redis.
 *    Fixes blocker-20 (cr-java-0067): in-memory HashMap cache replaced with Redis cache
 *    with TTL configured in application.properties (spring.cache.redis.time-to-live).
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
@EnableCaching
public class CloudConfig {
    // Redis connection is auto-configured by Spring Boot using spring.redis.* properties.
    // No additional bean definitions are required — Spring Boot auto-configuration
    // creates the RedisConnectionFactory and RedisCacheManager automatically.
}
