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
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AwsConfig — Spring configuration for AWS SDK v2 clients and Redis.
 *
 * <p>Provides beans for:</p>
 * <ul>
 *   <li>{@link S3Client} — Amazon S3 for cloud-native file/report storage</li>
 *   <li>{@link SecretsManagerClient} — AWS Secrets Manager for DB credentials
 *       and authentication secrets</li>
 *   <li>{@link SsmClient} — AWS Systems Manager Parameter Store for environment
 *       URLs and configuration</li>
 *   <li>{@link RedisTemplate} — Amazon ElastiCache for Redis for distributed
 *       session state and bounded caching</li>
 * </ul>
 *
 * <p>All AWS clients use the default credential provider chain, which resolves
 * credentials from (in order): environment variables, system properties,
 * AWS credentials file, EC2/ECS instance metadata, and IAM roles — ensuring
 * compatibility with all AWS deployment targets (ECS, EKS, EC2, Lambda).</p>
 */
@Configuration
@EnableRedisHttpSession
public class AwsConfig {

    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    @Value("${spring.redis.host:${REDIS_HOST:localhost}}")
    private String redisHost;

    @Value("${spring.redis.port:${REDIS_PORT:6379}}")
    private int redisPort;

    // -----------------------------------------------------------------------
    // AWS SDK v2 clients
    // -----------------------------------------------------------------------

    /**
     * Amazon S3 client — used by {@link ReportService} to store reports and
     * backups in S3 instead of the local file system.
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Secrets Manager client — used by {@link BookingService} to retrieve
     * database credentials and authentication secrets at runtime.
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Systems Manager client — used by {@link ReportService} to retrieve
     * environment-specific URLs from Parameter Store.
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    // -----------------------------------------------------------------------
    // Redis / ElastiCache
    // -----------------------------------------------------------------------

    /**
     * Redis connection factory pointing to Amazon ElastiCache for Redis.
     * Host and port are injected from environment variables / application.properties
     * so they can differ per deployment environment.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * Redis template with JSON serialisation for storing complex objects
     * (booking maps, session attributes) in ElastiCache.
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
