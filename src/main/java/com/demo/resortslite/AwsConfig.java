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
 * All AWS clients use the default credential provider chain, which resolves
 * credentials in the following order (suitable for ECS/EKS/Elastic Beanstalk):
 *   1. Environment variables (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY)
 *   2. Java system properties
 *   3. AWS credentials file (~/.aws/credentials)
 *   4. ECS task role / EC2 instance profile (preferred for cloud deployments)
 *
 * The AWS region is externalised via the AWS_REGION environment variable
 * (or the aws.region application property), defaulting to us-east-1.
 */
@Configuration
public class AwsConfig {

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Amazon S3 client — used by ReportService to store report files durably
     * instead of writing to the ephemeral local file system.
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Secrets Manager client — used by BookingService to retrieve database
     * credentials and authentication secrets at runtime, replacing hard-coded values.
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS SSM Parameter Store client — used by BookingController and ReportService
     * to resolve environment-specific URLs and port numbers at runtime, replacing
     * hard-coded configuration values.
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
