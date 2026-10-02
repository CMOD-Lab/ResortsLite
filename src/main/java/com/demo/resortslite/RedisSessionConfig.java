package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Redis Session Configuration for Amazon ElastiCache.
 *
 * <p><strong>cr-java-0065 fix:</strong> Configures Spring Session to use Amazon ElastiCache
 * for Redis as the centralised session store, replacing the previous in-process
 * {@code HttpSession} that was bound to a single EC2 instance. With this configuration:
 * <ul>
 *   <li>All session attributes ({@code lastBooking}, {@code guestName}) are stored in and
 *       retrieved from the shared Redis cluster, making every application instance
 *       fully stateless.</li>
 *   <li>AWS ALB can distribute requests across any number of EC2 instances without
 *       sticky-session routing rules.</li>
 *   <li>Auto Scaling scale-in events no longer cause session data loss.</li>
 *   <li>Session TTL is controlled by {@code maxInactiveIntervalInSeconds} (default 1800 s).</li>
 * </ul>
 *
 * <p>Required application properties (set via environment variables or AWS SSM Parameter Store):
 * <pre>
 *   spring.redis.host   — ElastiCache primary endpoint  (env: SPRING_REDIS_HOST)
 *   spring.redis.port   — ElastiCache port, default 6379 (env: SPRING_REDIS_PORT)
 *   spring.redis.password — ElastiCache auth token       (env: SPRING_REDIS_PASSWORD)
 * </pre>
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    /**
     * ElastiCache primary endpoint hostname.
     * Resolved from AWS SSM Parameter Store ({@code /resortslite/redis/host}),
     * environment variable {@code SPRING_REDIS_HOST}, or defaults to {@code localhost}
     * for local development.
     */
    @Value("${spring.redis.host:${SPRING_REDIS_HOST:localhost}}")
    private String redisHost;

    /**
     * ElastiCache port (default 6379).
     * Resolved from environment variable {@code SPRING_REDIS_PORT} or defaults to 6379.
     */
    @Value("${spring.redis.port:${SPRING_REDIS_PORT:6379}}")
    private int redisPort;

    /**
     * ElastiCache auth token (optional — required when in-transit encryption is enabled).
     * Resolved from environment variable {@code SPRING_REDIS_PASSWORD}.
     * Leave empty for clusters without authentication.
     */
    @Value("${spring.redis.password:${SPRING_REDIS_PASSWORD:}}")
    private String redisPassword;

    /**
     * Lettuce connection factory pointing at the Amazon ElastiCache primary endpoint.
     *
     * <p>Lettuce is the recommended Redis client for Spring Boot 2.x and supports
     * ElastiCache cluster mode, TLS, and connection pooling out of the box.
     *
     * @return configured {@link LettuceConnectionFactory}
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }
        return new LettuceConnectionFactory(config);
    }
}
