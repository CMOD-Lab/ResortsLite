package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * AWS configuration beans.
 *
 * <p>Provides singleton {@link S3Client} and {@link SecretsManagerClient} beans
 * used across the application.
 *
 * <p>The AWS region is read from the {@code cloud.aws.region} environment variable /
 * application property so that no region is hard-coded in source code.
 *
 * <p>Credentials are resolved automatically by the
 * {@code DefaultCredentialsProvider} chain (environment variables,
 * EC2/ECS instance profile, AWS config file, etc.) — no credentials
 * are hard-coded in source code.
 */
@Configuration
public class AwsS3Config {

    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Creates and exposes an AWS SDK v2 {@link S3Client} bean.
     *
     * <p>Replaces hard-coded local file-system operations (cr-java-0061 fix).
     *
     * @return configured {@link S3Client}
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Creates and exposes an AWS SDK v2 {@link SecretsManagerClient} bean.
     *
     * <p>Used by {@link BookingService} to retrieve database credentials at
     * startup from AWS Secrets Manager, eliminating hard-coded credentials
     * from source code (cr-java-0069 fix).
     *
     * @return configured {@link SecretsManagerClient}
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
