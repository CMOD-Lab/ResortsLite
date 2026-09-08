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
 * These clients are used by:
 *  - ReportService: S3Client (S3 object storage), SsmClient (SSM Parameter Store)
 *  - BookingService: SecretsManagerClient (Secrets Manager for DB + auth credentials)
 *
 * All clients use the AWS region configured via the cloud.aws.region.static property,
 * which defaults to us-east-1 and can be overridden by the AWS_REGION environment variable.
 *
 * Authentication uses the default AWS credential provider chain (IAM role, env vars,
 * ~/.aws/credentials) — no credentials are hard-coded in source code.
 */
@Configuration
public class AwsConfig {

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * Amazon S3 client for report storage.
     * Supports blocker-1 to blocker-7 (cr-java-0061, cr-java-0062, cr-java-0063).
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
     * AWS Secrets Manager client for retrieving database and authentication credentials.
     * Supports blocker-8, blocker-9 (cr-java-0069) and blocker-18 (cr-java-0090).
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
     * AWS SSM Parameter Store client for retrieving externalized configuration values.
     * Supports blocker-10, blocker-11 (cr-java-0071) and blocker-12 (cr-java-0077).
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
