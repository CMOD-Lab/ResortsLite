package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — generates resort reports and stores them in Amazon S3.
 *
 * <p>All hard-coded absolute file paths (/var/legacy/reports/, C:\ResortBackups\nightly\)
 * have been replaced with Amazon S3 object storage operations using AWS SDK for Java v2
 * (fix for cr-java-0061: Hard-coded File Paths).
 *
 * <p>All local file system write operations (FileWriter, File.mkdirs) have been replaced
 * with Amazon S3 PutObject calls to ensure data durability in containerised/cloud environments
 * (fix for cr-java-0062: Local File System Write Operations — line 42).
 *
 * <p>The hard-coded server port constant (SERVER_PORT = 8080) has been replaced with an
 * environment-variable-backed {@code @Value} injection (fix for cr-java-0077: Hard-coded Ports).
 * At runtime the value is supplied via the {@code SERVER_PORT} environment variable, which ECS /
 * EKS / Elastic Beanstalk injects from AWS Systems Manager Parameter Store, enabling dynamic
 * port assignment by the container orchestration platform.
 *
 * <p>Required environment variables / application properties:
 * <ul>
 *   <li>{@code AWS_REPORTS_BUCKET}  — S3 bucket name for generated reports</li>
 *   <li>{@code AWS_REGION}          — AWS region (e.g. us-east-1)</li>
 *   <li>{@code SERVER_PORT}         — HTTP port the application listens on (e.g. 8080)</li>
 * </ul>
 */
@Service
public class ReportService {

    /**
     * S3 bucket name for report storage, injected from the environment variable
     * AWS_REPORTS_BUCKET (replaces hard-coded "/var/legacy/reports/" — cr-java-0061,
     * and replaces local FileWriter write target — cr-java-0062).
     */
    @Value("${aws.reports.bucket:${AWS_REPORTS_BUCKET:resort-reports-bucket}}")
    private String reportsBucket;

    /**
     * AWS region for the S3 client, injected from the environment variable
     * AWS_REGION (replaces hard-coded path dependency — cr-java-0061).
     */
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Server port injected from the {@code SERVER_PORT} environment variable
     * (fix for cr-java-0077: Hard-coded Ports — line 28).
     *
     * <p>Replaces: {@code private static final int SERVER_PORT = 8080;}
     *
     * <p>The value is sourced at runtime from the {@code SERVER_PORT} environment variable,
     * which ECS task definitions, EKS pod specs, or Elastic Beanstalk environment properties
     * populate from AWS Systems Manager Parameter Store (e.g. parameter
     * {@code /resortslite/server/port}). This enables container orchestration platforms to
     * perform dynamic port assignment and eliminates deployment conflicts caused by fixed ports.
     *
     * <p>Falls back to {@code 8080} when the environment variable is absent (local development).
     */
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    /**
     * Generates a monthly resort report CSV and uploads it to Amazon S3.
     *
     * <p>Fix for cr-java-0062 (Local File System Write Operations — line 42):
     * The original code used {@code new File(REPORT_BASE_PATH).mkdirs()} and
     * {@code new FileWriter(fullPath)} to write report data to the local file system.
     * In cloud/containerised environments the local file system is ephemeral; data written
     * locally is lost on container restart or scale-out events.
     *
     * <p>Remediation: CSV content is now built in-memory and uploaded directly to Amazon S3
     * via {@code S3Client.putObject()}, ensuring durable, highly-available storage that
     * survives container restarts and horizontal scaling.
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string (e.g. "2024")
     * @return result map containing status and S3 object key (or error message)
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // S3 object key replaces the former absolute file path (cr-java-0061 / cr-java-0062)
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0062 fix (line 42): Build CSV content entirely in memory.
            // Replaces: new File(REPORT_BASE_PATH).mkdirs() + new FileWriter(fullPath)
            // No local directory creation or local file write operations are performed.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);

            // cr-java-0062 fix (line 42): Upload report directly to Amazon S3.
            // Replaces: writer.write(...) / writer.close() to local file system.
            // S3 provides durable, cloud-native object storage — data persists across
            // container restarts, scale-out events, and node failures.
            S3Client s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));
            s3Client.close();

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucket);
            result.put("s3Key", objectKey);
            // cr-java-0077 fix: use injected serverPort instead of hard-coded constant
            result.put("serverPort", serverPort);

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
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    /**
     * Returns system information including S3 storage references and the active server port.
     *
     * <p>Replaces former hard-coded file path references with S3 bucket/key information
     * (fix for cr-java-0061 / cr-java-0062 — lines 23, 37, 42).
     *
     * <p>The {@code serverPort} entry now reflects the environment-variable-injected value
     * rather than the former hard-coded constant (fix for cr-java-0077 — line 28).
     *
     * @return map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 fix: replaced java.util.Date + SimpleDateFormat with java.time API.
        // Instant.now() always captures the current moment in UTC, eliminating server-local
        // timezone dependencies that cause scheduling failures in multi-region cloud deployments.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // Replaced hard-coded REPORT_BASE_PATH ("/var/legacy/reports/") with S3 bucket reference
        // (cr-java-0061 / cr-java-0062)
        info.put("reportsBucket", reportsBucket);
        // Replaced hard-coded BACKUP_PATH ("C:\\ResortBackups\\nightly\\") with S3 bucket reference
        // (cr-java-0061 / cr-java-0062)
        info.put("backupBucket", reportsBucket + "/backups");
        // cr-java-0077 fix: use injected serverPort instead of hard-coded constant
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
