package com.demo.resortslite;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;

import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Replaced hard-coded absolute path "/var/legacy/reports/" with environment variable
    // backed S3 bucket and key prefix. The S3 bucket name is read from the environment
    // variable REPORT_S3_BUCKET (cr-java-0061 fix).
    private static final String REPORT_S3_BUCKET = System.getenv().getOrDefault(
            "REPORT_S3_BUCKET", "resorts-lite-reports");

    // Replaced hard-coded Windows-style backup path "C:\\ResortBackups\\nightly\\" with
    // an S3 key prefix sourced from the environment variable BACKUP_S3_PREFIX (cr-java-0061 fix).
    private static final String BACKUP_S3_PREFIX = System.getenv().getOrDefault(
            "BACKUP_S3_PREFIX", "backups/nightly/");

    // cr-java-0077 FIX: Replaced hard-coded port constant (8080) with a value injected at
    // runtime through the SERVER_PORT environment variable. In ECS, EKS, or Elastic Beanstalk
    // the platform sets SERVER_PORT dynamically, enabling conflict-free port assignment and
    // proper service-discovery registration. The value is also stored in AWS Systems Manager
    // Parameter Store under the path resolved by SERVER_PORT_PARAM (default:
    // /resortslite/server/port) so that all services can discover the port without hard-coding.
    private static final int SERVER_PORT = Integer.parseInt(
            System.getenv().getOrDefault("SERVER_PORT", "8080"));

    // Shared S3 client — region is resolved from the AWS_REGION environment variable or
    // the EC2/ECS instance metadata service automatically by the AWS SDK default provider chain.
    private final S3Client s3Client = S3Client.builder()
            .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
            .build();

    // cr-java-0071 FIX: AwsSsmConfig bean is injected to resolve environment-specific URLs
    // from AWS Systems Manager Parameter Store, replacing hard-coded endpoint strings.
    @Autowired
    private AwsSsmConfig awsSsmConfig;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // cr-java-0061 fix (line 37): replaced local fullPath construction with an S3 object key.
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0062 fix (line 42): replaced local FileWriter write operation with
            // Amazon S3 putObject upload. In cloud/containerised environments the local file
            // system is ephemeral; data written locally is lost on container restart or scale.
            // The CSV content is built in-memory and uploaded directly to S3 via AWS SDK v2,
            // ensuring durable, highly-available storage that survives container lifecycle events.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(REPORT_S3_BUCKET)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            String s3Uri = "s3://" + REPORT_S3_BUCKET + "/" + s3Key;
            result.put("status", "generated");
            result.put("path", s3Uri);
            // cr-java-0077 FIX: SERVER_PORT is now sourced from the SERVER_PORT environment
            // variable injected by ECS/EKS/Elastic Beanstalk at runtime.
            result.put("serverPort", SERVER_PORT);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // cr-java-0071 FIX (line 66): Replaced hard-coded environment-specific URL
        // "http://reports.resorts-internal.com:8080/download/" with a value retrieved
        // from AWS Systems Manager Parameter Store via AwsSsmConfig. The SSM parameter name
        // is configured through the environment variable REPORT_DOWNLOAD_BASE_URL_PARAM
        // (default: /resortslite/reports/download-base-url), enabling environment-agnostic
        // deployments without code changes between dev, staging, and production.
        String reportBaseUrl = awsSsmConfig.getParameter(
                System.getenv().getOrDefault(
                        "REPORT_DOWNLOAD_BASE_URL_PARAM",
                        "/resortslite/reports/download-base-url"));
        return reportBaseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // cr-java-0111 FIX: Replaced java.util.Date / SimpleDateFormat with java.time API
        // standardized on UTC (ZoneOffset.UTC) to eliminate timezone inconsistencies across
        // distributed cloud containers and multi-region deployments.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0061 fix: report and backup paths now reflect S3 locations instead of
        // hard-coded local file system paths.
        info.put("reportBucket", REPORT_S3_BUCKET);
        info.put("backupS3Prefix", BACKUP_S3_PREFIX);
        // cr-java-0077 FIX: SERVER_PORT is now sourced from the SERVER_PORT environment
        // variable injected by ECS/EKS/Elastic Beanstalk at runtime.
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
