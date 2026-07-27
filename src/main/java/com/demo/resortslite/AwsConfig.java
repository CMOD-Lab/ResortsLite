package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AWS SDK v2 client configuration.
 *
 * Provides Spring-managed beans for:
 *  - Amazon S3 (report/file storage — replaces local file system)
 *  - AWS Secrets Manager (database credentials — replaces hard-coded values)
 *  - AWS Systems Manager Parameter Store (environment URLs and ports)
 *
 * All clients use the default credential provider chain, which resolves credentials
 * from environment variables, EC2 instance profiles, ECS task roles, or ~/.aws/credentials
 * — no credentials are hard-coded in source code.
 */
@Configuration
public class AwsConfig {

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * Amazon S3 client for durable report/file storage.
     * Replaces hard-coded local file paths (cr-java-0061, cr-java-0062, cr-java-0063).
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Secrets Manager client for retrieving database credentials at runtime.
     * Replaces hard-coded DB username/password in source code (cr-java-0069).
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Systems Manager client for retrieving environment-specific URLs and ports.
     * Replaces hard-coded environment URLs (cr-java-0071) and ports (cr-java-0077).
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
