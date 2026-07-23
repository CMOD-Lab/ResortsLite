package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AwsConfig provides Spring beans for AWS SDK v2 clients used throughout the application.
 *
 * <p>Clients configured here:
 * <ul>
 *   <li>{@link S3Client} — used by {@link ReportService} to store reports in Amazon S3,
 *       replacing all local file system operations (blockers 1-7).</li>
 *   <li>{@link SecretsManagerClient} — used by {@link BookingService} to retrieve database
 *       credentials and authentication tokens from AWS Secrets Manager, replacing
 *       hard-coded credentials (blockers 8, 9, 18).</li>
 *   <li>{@link SsmClient} — used by {@link ReportService} and {@link BookingController}
 *       to retrieve environment-specific URLs and port numbers from AWS Systems Manager
 *       Parameter Store, replacing hard-coded values (blockers 10, 11, 12).</li>
 * </ul>
 *
 * <p>The AWS region is injected from the {@code AWS_REGION} environment variable,
 * defaulting to {@code us-east-1}. In ECS/EKS/Elastic Beanstalk deployments, the
 * IAM task role or instance profile provides credentials automatically — no access
 * keys are stored in code or configuration files.
 */
@Configuration
public class AwsConfig {

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * Amazon S3 client for cloud-native object storage.
     * Replaces all java.io.File and FileWriter operations in ReportService.
     *
     * @return configured S3Client
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Secrets Manager client for centralized, encrypted secret storage.
     * Used to retrieve database credentials and authentication tokens,
     * replacing hard-coded values and file-based authentication.
     *
     * @return configured SecretsManagerClient
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Systems Manager client for Parameter Store access.
     * Used to retrieve environment-specific URLs and port numbers at runtime,
     * enabling environment-agnostic deployments.
     *
     * @return configured SsmClient
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
