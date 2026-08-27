package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AwsClientConfig provides Spring-managed beans for AWS SDK v2 clients.
 *
 * <p>All clients use the AWS default credential provider chain, which resolves
 * credentials in the following order:
 * <ol>
 *   <li>Environment variables (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY)</li>
 *   <li>Java system properties</li>
 *   <li>AWS credentials file (~/.aws/credentials)</li>
 *   <li>ECS container credentials (when running on ECS/Fargate)</li>
 *   <li>EC2 instance profile / IAM role (when running on EC2/EKS)</li>
 * </ol>
 *
 * <p>No credentials are hard-coded in this configuration class.
 */
@Configuration
public class AwsClientConfig {

    /**
     * AWS region injected from environment variable AWS_REGION.
     * Defaults to us-east-1 if not set.
     */
    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Amazon S3 client bean for cloud-native object storage.
     * Used by {@link ReportService} to replace local file system operations.
     *
     * @return configured S3Client instance
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Secrets Manager client bean for externalized credential management.
     * Used by {@link BookingService} to replace hard-coded DB credentials and
     * file-based authentication.
     *
     * @return configured SecretsManagerClient instance
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Systems Manager client bean for Parameter Store access.
     * Used by {@link ReportService} to retrieve externalized environment URLs.
     *
     * @return configured SsmClient instance
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
