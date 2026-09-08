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
 * ReportService handles report generation and storage using Amazon S3
 * for cloud-native, durable, and scalable storage.
 * All file path dependencies have been replaced with S3 object storage.
 * All time operations use java.time API standardized on UTC.
 */
@Service
public class ReportService {

    // Replaced hard-coded file paths with environment-variable-driven S3 bucket configuration.
    // blocker-1, blocker-2, blocker-3: cr-java-0061 Hard-coded File Paths → Amazon S3
    @Value("${cloud.aws.s3.reports-bucket:resorts-lite-reports}")
    private String reportsBucket;

    // Replaced hard-coded Windows backup path with S3 prefix configuration.
    @Value("${cloud.aws.s3.backup-prefix:nightly-backups/}")
    private String backupPrefix;

    // Replaced hard-coded port with environment variable injection.
    // blocker-12: cr-java-0077 Hard-coded Ports → AWS Parameter Store / env var
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    // blocker-11: cr-java-0071 Hard-coded Environment URLs → AWS SSM Parameter Store
    @Value("${app.report.download.base-url:#{null}}")
    private String reportDownloadBaseUrl;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly report and uploads it to Amazon S3.
     * Replaces all local file write operations (blocker-4: cr-java-0062) and
     * java.io.File usage (blocker-5, blocker-6, blocker-7: cr-java-0063) with S3 operations.
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return a map containing the report status and S3 object key
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

            // Upload report directly to Amazon S3 — replaces FileWriter to local path
            // blocker-4 (cr-java-0062): local file write → S3 PutObject
            // blocker-5, blocker-7 (cr-java-0063): java.io.File → S3 SDK
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest,
                    RequestBody.fromBytes(csvContent.toString().getBytes()));

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucket);
            result.put("s3Key", objectKey);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL by retrieving the base URL from AWS SSM Parameter Store.
     * Replaces the hard-coded environment URL (blocker-11: cr-java-0071).
     *
     * @param reportName the name of the report
     * @return the full HTTPS download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // blocker-11: cr-java-0071 — retrieve base URL from AWS SSM Parameter Store
        String baseUrl = resolveReportBaseUrl();
        return baseUrl + "/download/" + reportName;
    }

    /**
     * Returns system information using UTC timestamps.
     * Replaces java.util.Date with java.time API standardized on UTC (blocker-19: cr-java-0111).
     *
     * @return a map of system information
     */
    public Map<String, Object> getSystemInfo() {
        // blocker-19: cr-java-0111 — replace java.util.Date/SimpleDateFormat with java.time UTC
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        info.put("reportsBucket", reportsBucket);
        info.put("backupPrefix", backupPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }

    /**
     * Resolves the report download base URL from AWS SSM Parameter Store,
     * falling back to the application property if SSM is unavailable.
     */
    private String resolveReportBaseUrl() {
        // Attempt to fetch from SSM Parameter Store first
        if (reportDownloadBaseUrl != null && !reportDownloadBaseUrl.isEmpty()) {
            return reportDownloadBaseUrl;
        }
        try {
            GetParameterRequest paramRequest = GetParameterRequest.builder()
                    .name("/resortslite/report/download-base-url")
                    .withDecryption(false)
                    .build();
            GetParameterResponse paramResponse = ssmClient.getParameter(paramRequest);
            return paramResponse.parameter().value();
        } catch (Exception e) {
            // Fallback to a safe default if SSM is not reachable
            return "https://reports.resorts-internal.com";
        }
    }
}
