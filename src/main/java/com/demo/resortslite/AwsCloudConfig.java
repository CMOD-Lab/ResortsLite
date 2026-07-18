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

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AwsCloudConfig wires up all AWS SDK v2 clients and Redis configuration
 * required for cloud-native operation on AWS.
 *
 * <ul>
 *   <li>S3Client — for report and backup storage (replaces local file system)</li>
 *   <li>SecretsManagerClient — for database and auth credentials (replaces hard-coded values)</li>
 *   <li>SsmClient — for environment URLs and port configuration (replaces hard-coded constants)</li>
 *   <li>RedisTemplate — for distributed caching and session state via ElastiCache</li>
 * </ul>
 */
@Configuration
@EnableRedisHttpSession
public class AwsCloudConfig {

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    // ─── AWS SDK v2 Clients ───────────────────────────────────────────────────

    /**
     * Amazon S3 client for cloud-native object storage.
     * Credentials are resolved via the AWS Default Credentials Provider Chain
     * (IAM role, environment variables, ~/.aws/credentials).
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * AWS Secrets Manager client for retrieving database and auth credentials.
     * Eliminates all hard-coded credential constants from source code.
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * AWS Systems Manager client for retrieving Parameter Store values
     * (environment URLs, port numbers, and other externalized configuration).
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    // ─── Redis / ElastiCache Configuration ───────────────────────────────────

    /**
     * Redis connection factory pointing to Amazon ElastiCache.
     * Host and port are injected from environment variables — not hard-coded.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisHost);
        config.setPort(redisPort);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }
        return new LettuceConnectionFactory(config);
    }

    /**
     * RedisTemplate configured with JSON serialization for distributed caching.
     * Replaces the static in-memory HashMap (cr-java-0067) with a TTL-aware,
     * cross-instance consistent cache backed by Amazon ElastiCache for Redis.
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
