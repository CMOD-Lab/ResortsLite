package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AwsConfig wires up AWS SDK v2 clients as Spring beans.
 *
 * All clients use the AWS region injected via the AWS_REGION environment variable
 * and rely on the default credential provider chain (IAM role, environment variables,
 * ~/.aws/credentials) — no hard-coded credentials anywhere.
 *
 * Supports:
 * - Amazon S3 (report storage — blockers 1-7)
 * - AWS Secrets Manager (DB and auth credentials — blockers 8/9/18)
 * - AWS SSM Parameter Store (environment URLs and ports — blockers 10/11/12)
 */
@Configuration
public class AwsConfig {

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * Amazon S3 client for cloud-native report storage.
     * Replaces all java.io.File / FileWriter local file operations.
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
     * AWS Secrets Manager client for retrieving database and auth credentials.
     * Eliminates hard-coded DB_USER, DB_PASS, and file-based auth tokens.
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
     * AWS Systems Manager client for retrieving environment-specific URLs and ports.
     * Replaces hard-coded inventory/report service URLs and port numbers.
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
