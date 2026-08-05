package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AwsCloudConfig — Spring configuration class that wires up all AWS SDK v2
 * clients and the Redis/ElastiCache connection factory required for cloud-native
 * operation.
 *
 * <p>All connection parameters are injected from environment variables that are
 * populated at deploy time from AWS SSM Parameter Store / Secrets Manager,
 * following 12-factor app principles (no hard-coded values in source code).</p>
 */
@Configuration
public class AwsCloudConfig {

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    @Value("${spring.redis.host:${REDIS_HOST:localhost}}")
    private String redisHost;

    @Value("${spring.redis.port:${REDIS_PORT:6379}}")
    private int redisPort;

    // -----------------------------------------------------------------------
    // AWS SDK v2 Clients
    // -----------------------------------------------------------------------

    /**
     * Amazon S3 client — used by {@link ReportService} to upload report files
     * to S3, replacing all local {@code java.io.File} / {@code FileWriter}
     * operations (cr-java-0061, cr-java-0062, cr-java-0063 / blockers 1-7).
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Secrets Manager client — used by {@link BookingService} to retrieve
     * database credentials at startup, replacing hard-coded DB_HOST, DB_USER,
     * DB_PASS constants (cr-java-0069 / blockers 8-9) and file-based
     * authentication (cr-java-0090 / blocker-18).
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS SSM Parameter Store client — used by {@link ReportService} to
     * retrieve environment-specific URLs and configuration values, replacing
     * hard-coded environment URLs (cr-java-0071 / blockers 10-11) and
     * hard-coded port numbers (cr-java-0077 / blocker-12).
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    // -----------------------------------------------------------------------
    // Redis / Amazon ElastiCache
    // -----------------------------------------------------------------------

    /**
     * Redis connection factory pointing to Amazon ElastiCache for Redis.
     * Used by Spring Session (replacing HttpSession — cr-java-0065 / blockers 13-17)
     * and by the booking cache with TTL (replacing unbounded HashMap —
     * cr-java-0067 / blocker-20).
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * RedisTemplate configured with JSON serialisation for storing booking
     * objects and session attributes in ElastiCache for Redis.
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
