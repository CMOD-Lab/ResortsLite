package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AwsCloudConfig — Spring configuration for AWS SDK v2 clients and Redis.
 *
 * <p>Provides beans for:
 * <ul>
 *   <li>{@link S3Client} — Amazon S3 for durable report storage (cr-java-0061/0062/0063)</li>
 *   <li>{@link SecretsManagerClient} — AWS Secrets Manager for credential retrieval (cr-java-0069/0090)</li>
 *   <li>{@link SsmClient} — AWS Systems Manager Parameter Store for URL/port config (cr-java-0071/0077)</li>
 *   <li>{@link RedisTemplate} — Amazon ElastiCache for Redis session and cache (cr-java-0065/0067)</li>
 * </ul>
 * </p>
 */
@Configuration
@EnableCaching
public class AwsCloudConfig {

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * Amazon S3 client for cloud-native object storage.
     * Replaces all local java.io.File / FileWriter operations.
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Secrets Manager client for secure credential retrieval.
     * Replaces hard-coded database credentials and file-based authentication.
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Systems Manager client for Parameter Store access.
     * Replaces hard-coded environment URLs and port numbers.
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Redis template configured with JSON serialization for distributed session
     * and cache storage backed by Amazon ElastiCache for Redis.
     * Replaces in-memory HTTP session state and unbounded HashMap cache.
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
