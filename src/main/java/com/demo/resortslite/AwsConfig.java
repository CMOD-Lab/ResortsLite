package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AwsConfig — Spring configuration class that exposes AWS SDK v2 clients as beans.
 *
 * All clients use the default credential provider chain, which automatically resolves
 * credentials from (in order):
 *   1. Environment variables (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY)
 *   2. Java system properties
 *   3. AWS credentials file (~/.aws/credentials)
 *   4. ECS task role / EC2 instance profile (preferred in cloud deployments)
 *
 * The AWS region is injected from the AWS_REGION environment variable, which is
 * automatically set by ECS, EKS, and Elastic Beanstalk.
 */
@Configuration
public class AwsConfig {

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    /**
     * Amazon S3 client for report storage.
     * Used by ReportService to replace local file system operations (blockers 1-7).
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Secrets Manager client for database and authentication credentials.
     * Used by BookingService to replace hard-coded credentials (blockers 8/9/18).
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Systems Manager client for Parameter Store lookups.
     * Used by ReportService to replace hard-coded environment URLs and ports (blockers 10-12).
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
