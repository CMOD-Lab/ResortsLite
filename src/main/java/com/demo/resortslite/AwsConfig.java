package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AWS cloud configuration — wires up AWS SDK v2 clients for:
 * - Amazon S3 (replaces local file system operations)
 * - AWS Secrets Manager (replaces hard-coded database and auth credentials)
 * - AWS Systems Manager Parameter Store (replaces hard-coded URLs and ports)
 *
 * All clients use the default credential provider chain (IAM role / environment variables).
 */
@Configuration
public class AwsConfig {

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * Amazon S3 client for cloud-native object storage.
     * Replaces all java.io.File and local FileWriter operations.
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Amazon S3 Presigner for generating pre-signed download URLs.
     * Replaces hard-coded HTTP report download URLs.
     */
    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Secrets Manager client for retrieving database and auth credentials.
     * Replaces hard-coded DB_USER, DB_PASS, DB_HOST constants and file-based auth.
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Systems Manager (SSM) client for retrieving Parameter Store values.
     * Replaces hard-coded environment URLs and port numbers.
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
