package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService handles report generation and storage using Amazon S3 for
 * cloud-native, durable, and scalable storage. All file path dependencies
 * have been replaced with S3 object storage operations. Environment-specific
 * URLs and port numbers are externalized to AWS Systems Manager Parameter Store.
 * Time operations use java.time API standardized on UTC.
 */
@Service
public class ReportService {

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    /**
     * S3 bucket name injected from environment variable (set via ECS/EKS task definition
     * or Elastic Beanstalk environment configuration). Replaces hard-coded file paths.
     */
    @Value("${cloud.aws.s3.report-bucket:resorts-reports-bucket}")
    private String reportBucket;

    /**
     * Report download base URL injected from environment variable.
     * Replaces hard-coded environment-specific URL (blocker-11 / cr-java-0071).
     */
    @Value("${app.report.download-url:#{null}}")
    private String reportDownloadUrlOverride;

    /**
     * Server port injected from environment variable.
     * Replaces hard-coded port 8080 (blocker-12 / cr-java-0077).
     */
    @Value("${server.port:${PORT:8080}}")
    private int serverPort;

    /**
     * SSM Parameter Store key for the report download base URL.
     */
    @Value("${aws.ssm.report-download-url-param:/resortslite/report/download-url}")
    private String reportDownloadUrlParam;

    /**
     * SSM Parameter Store key for the server port.
     */
    @Value("${aws.ssm.server-port-param:/resortslite/server/port}")
    private String serverPortParam;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly report and uploads it to Amazon S3.
     * Replaces all local file system operations (java.io.File, FileWriter) with
     * S3 PutObject calls for cloud-native durable storage (blockers 1-7).
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return result map containing status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes();

            // Upload report directly to Amazon S3 — replaces FileWriter to local path
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", objectKey);
            result.put("serverPort", getServerPort());

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL by retrieving the base URL from
     * AWS Systems Manager Parameter Store (blocker-11 / cr-java-0071).
     * Falls back to environment variable override if SSM is unavailable.
     *
     * @param reportName the name of the report object in S3
     * @return HTTPS download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        String baseUrl = resolveReportDownloadBaseUrl();
        return baseUrl + "/" + reportName;
    }

    /**
     * Returns system information using UTC timestamps (blocker-19 / cr-java-0111).
     * Port is resolved from AWS SSM Parameter Store (blocker-12 / cr-java-0077).
     *
     * @return map of system information
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time Instant with UTC for cloud-safe timestamp (replaces java.util.Date)
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        info.put("reportBucket", reportBucket);
        info.put("serverPort", getServerPort());
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Resolves the report download base URL from AWS SSM Parameter Store.
     * Falls back to environment variable or a safe default if SSM is unavailable.
     */
    private String resolveReportDownloadBaseUrl() {
        // Use environment variable override if explicitly set
        if (reportDownloadUrlOverride != null && !reportDownloadUrlOverride.isEmpty()) {
            return reportDownloadUrlOverride;
        }
        // Retrieve from AWS Systems Manager Parameter Store
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(reportDownloadUrlParam)
                            .withDecryption(false)
                            .build());
            return response.parameter().value();
        } catch (Exception e) {
            // Fall back to S3 pre-signed URL pattern if SSM is unavailable
            return "https://" + reportBucket + ".s3.amazonaws.com/reports";
        }
    }

    /**
     * Resolves the server port from AWS SSM Parameter Store or environment variable.
     * Replaces hard-coded port constant (blocker-12 / cr-java-0077).
     */
    private int getServerPort() {
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(serverPortParam)
                            .withDecryption(false)
                            .build());
            return Integer.parseInt(response.parameter().value());
        } catch (Exception e) {
            return serverPort;
        }
    }
}
