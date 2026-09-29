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
 *  - Amazon S3 (replaces local file system — cr-java-0061/0062/0063)
 *  - AWS Secrets Manager (replaces hard-coded credentials — cr-java-0069/cr-java-0090)
 *  - AWS SSM Parameter Store (replaces hard-coded URLs/ports — cr-java-0071/cr-java-0077)
 *
 * The AWS region is injected via the environment variable AWS_REGION (12-factor).
 */
@Configuration
public class AwsConfig {

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * Amazon S3 client — used by ReportService to store report files
     * instead of writing to the local file system (blocker-1 to 7).
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Secrets Manager client — used by BookingService to retrieve
     * database credentials and authentication secrets at runtime
     * (blocker-8, 9, 18).
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS SSM Parameter Store client — used by BookingController and
     * ReportService to retrieve environment-specific URLs at runtime
     * (blocker-10, 11, 12).
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
