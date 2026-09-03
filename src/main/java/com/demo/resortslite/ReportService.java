package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-ready service for report generation and storage.
 *
 * Cloud readiness changes applied:
 *  - cr-java-0061 (blockers 1, 2, 3): Hard-coded absolute file paths (/var/legacy/reports/,
 *    C:\ResortBackups\nightly\) replaced with Amazon S3 bucket configuration injected via
 *    environment variable / application.properties.
 *  - cr-java-0062 (blocker-4): Local file write operations replaced with Amazon S3 PutObject
 *    calls using AWS SDK for Java v2.
 *  - cr-java-0063 (blockers 5, 6, 7): java.io.File and FileWriter usage replaced with S3Client
 *    PutObject for durable, scalable cloud-native storage.
 *  - cr-java-0071 (blocker-11): Hard-coded report download URL replaced with S3 pre-signed URL
 *    generated at runtime, eliminating environment-specific hard-coded endpoints.
 *  - cr-java-0077 (blocker-12): Hard-coded SERVER_PORT constant replaced with value injected
 *    from environment variable / application.properties (AWS SSM Parameter Store at runtime).
 *  - cr-java-0111 (blocker-19): java.util.Date / SimpleDateFormat replaced with java.time API
 *    (Instant, ZonedDateTime) standardized on UTC for cloud-safe timestamp generation.
 */
@Service
public class ReportService {

    /**
     * S3 bucket name for report storage.
     * Injected from environment variable REPORT_S3_BUCKET / application.properties.
     * Fixes blockers 1, 2, 3, 4, 5, 6, 7 (cr-java-0061, cr-java-0062, cr-java-0063).
     */
    @Value("${report.s3.bucket:resortslite-reports}")
    private String reportS3Bucket;

    /**
     * S3 key prefix (folder) for reports.
     * Replaces hard-coded /var/legacy/reports/ path.
     * Fixes blockers 1, 2, 3 (cr-java-0061).
     */
    @Value("${report.s3.prefix:reports/}")
    private String reportS3Prefix;

    /**
     * AWS region for S3 and pre-signer clients.
     * Injected from environment variable AWS_REGION / application.properties.
     */
    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Server port — injected from environment variable SERVER_PORT / application.properties.
     * Fixes blocker-12 (cr-java-0077): hard-coded port 8080 replaced with externalized config.
     * In AWS ECS/EKS, the container port is set dynamically via task definition.
     */
    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * Report download base URL — injected from environment variable / application.properties.
     * Fixes blocker-11 (cr-java-0071): hard-coded URL replaced with externalized config.
     * At runtime, S3 pre-signed URLs are generated instead of static hard-coded endpoints.
     */
    @Value("${report.download.base.url:}")
    private String reportDownloadBaseUrl;

    /**
     * Generate a monthly report and upload it to Amazon S3.
     * Replaces local file system write operations with S3 PutObject.
     * Fixes blockers 1-7 (cr-java-0061, cr-java-0062, cr-java-0063).
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return result map with status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 key replaces the hard-coded local file path /var/legacy/reports/<fileName>
        // Fixes blockers 1, 2, 3 (cr-java-0061).
        String s3Key = reportS3Prefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency.
            // Fixes blocker-4 (cr-java-0062) and blockers 5, 6, 7 (cr-java-0063).
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes("UTF-8");

            // Upload report to Amazon S3 using AWS SDK for Java v2.
            // Replaces FileWriter / java.io.File operations.
            S3Client s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportS3Bucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));
            s3Client.close();

            result.put("status", "generated");
            result.put("s3Bucket", reportS3Bucket);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Build a pre-signed S3 URL for report download.
     * Fixes blocker-11 (cr-java-0071): replaces hard-coded
     * "http://reports.resorts-internal.com:8080/download/<name>" with a
     * dynamically generated S3 pre-signed URL valid for 1 hour.
     *
     * @param reportName the report file name (S3 object key suffix)
     * @return pre-signed HTTPS URL for the report object in S3
     */
    public String buildReportDownloadUrl(String reportName) {
        // Generate a pre-signed S3 URL — no hard-coded environment-specific endpoint.
        // Fixes blocker-11 (cr-java-0071).
        String s3Key = reportS3Prefix + reportName;

        S3Presigner presigner = S3Presigner.builder()
                .region(Region.of(awsRegion))
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(1))
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(reportS3Bucket)
                        .key(s3Key)
                        .build())
                .build();

        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
        String presignedUrl = presignedRequest.url().toString();
        presigner.close();

        return presignedUrl;
    }

    /**
     * Return system information using UTC timestamps.
     * Fixes blocker-19 (cr-java-0111): java.util.Date / SimpleDateFormat replaced with
     * java.time.Instant and ZonedDateTime standardized on UTC.
     *
     * @return system info map with UTC-based timestamp
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time API with explicit UTC zone — fixes blocker-19 (cr-java-0111).
        // Eliminates server-local timezone dependency for cloud multi-region deployments.
        ZonedDateTime nowUtc = Instant.now().atZone(ZoneOffset.UTC);
        String timestamp = nowUtc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));

        Map<String, Object> info = new HashMap<>();
        // S3 bucket/prefix replace hard-coded local paths — fixes blockers 1-3 (cr-java-0061).
        info.put("reportS3Bucket", reportS3Bucket);
        info.put("reportS3Prefix", reportS3Prefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
