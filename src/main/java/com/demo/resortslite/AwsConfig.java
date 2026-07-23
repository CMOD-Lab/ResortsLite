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
 * Clients configured here:
 * - S3Client: for report storage (replaces local file system — cr-java-0061/0062/0063)
 * - SecretsManagerClient: for database credentials (replaces hard-coded creds — cr-java-0069/0090)
 * - SsmClient: for environment URLs and port config (replaces hard-coded values — cr-java-0071/0077)
 *
 * All clients use the AWS region injected via environment variable (AWS_REGION).
 * Credentials are resolved automatically via the AWS Default Credential Provider Chain
 * (IAM role attached to ECS task / EC2 instance / Lambda — no hard-coded keys).
 */
@Configuration
public class AwsConfig {

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * Amazon S3 client for cloud-native object storage.
     * Replaces java.io.File / FileWriter local file system operations.
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
     * AWS Secrets Manager client for retrieving database credentials and
     * authentication secrets at runtime — no hard-coded credentials in source code.
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
     * AWS Systems Manager (SSM) Parameter Store client for retrieving
     * environment-specific URLs and port configuration at runtime.
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
