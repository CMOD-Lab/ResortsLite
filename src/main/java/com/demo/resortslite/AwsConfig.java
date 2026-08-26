package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AWS SDK v2 and Spring Session / Redis configuration.
 *
 * <p>Provides Spring-managed beans for:
 * <ul>
 *   <li>Amazon S3 client — used by {@link ReportService} to replace local file I/O
 *       (cr-java-0061, cr-java-0062, cr-java-0063)</li>
 *   <li>AWS Secrets Manager client — used by {@link BookingService} to retrieve
 *       database credentials and authentication secrets (cr-java-0069, cr-java-0090)</li>
 *   <li>AWS SSM Parameter Store client — used by {@link BookingController} and
 *       {@link ReportService} to resolve environment-specific URLs (cr-java-0071)</li>
 *   <li>RedisTemplate — used by {@link BookingController} to replace in-memory
 *       HttpSession state and unbounded HashMap cache with Amazon ElastiCache for
 *       Redis (cr-java-0065, cr-java-0067)</li>
 * </ul>
 */
@Configuration
@EnableRedisHttpSession
public class AwsConfig {

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Amazon S3 client bean.
     * Replaces java.io.File / FileWriter operations in ReportService (cr-java-0061,
     * cr-java-0062, cr-java-0063).
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Secrets Manager client bean.
     * Used to retrieve database credentials (cr-java-0069) and authentication
     * secrets (cr-java-0090) at runtime, replacing hard-coded values.
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Systems Manager Parameter Store client bean.
     * Used to resolve environment-specific URLs (cr-java-0071) and port
     * configuration (cr-java-0077) at runtime.
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * RedisTemplate bean for distributed caching and session state.
     *
     * <p>Replaces the static in-memory HashMap (cr-java-0067) and HttpSession
     * state (cr-java-0065) with Amazon ElastiCache for Redis.  Entries are
     * serialised as JSON for cross-instance compatibility.
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
