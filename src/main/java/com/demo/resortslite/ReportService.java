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
 * durable, cloud-native object storage instead of local file system paths.
 * Environment-specific URLs and port configuration are retrieved from
 * AWS Systems Manager Parameter Store at runtime.
 */
@Service
public class ReportService {

    // S3 bucket name injected from environment variable — no hard-coded paths
    @Value("${cloud.aws.s3.report-bucket:resorts-lite-reports}")
    private String reportBucket;

    // Report key prefix in S3 (replaces hard-coded /var/legacy/reports/)
    @Value("${cloud.aws.s3.report-prefix:reports/}")
    private String reportPrefix;

    // Backup key prefix in S3 (replaces hard-coded C:\ResortBackups\nightly\)
    @Value("${cloud.aws.s3.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    // Server port injected from environment variable (replaces hard-coded 8080)
    @Value("${SERVER_PORT:${server.port:8080}}")
    private int serverPort;

    // SSM parameter name for the report download base URL
    @Value("${cloud.aws.ssm.report-url-param:/resortslite/report/download-url}")
    private String reportUrlSsmParam;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly report CSV and uploads it to Amazon S3.
     * Replaces all local java.io.File / FileWriter operations with S3 PutObject calls.
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return result map containing status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 object key replaces the hard-coded absolute file path
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // Upload report to Amazon S3 (durable, cloud-native storage)
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();
            s3Client.putObject(putRequest, RequestBody.fromString(csvContent.toString()));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", s3Key);
            // Server port sourced from environment variable, not hard-coded
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL by retrieving the base URL from
     * AWS Systems Manager Parameter Store instead of using a hard-coded value.
     *
     * @param reportName the name of the report file
     * @return the full HTTPS download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // Retrieve environment-specific base URL from SSM Parameter Store
        // Replaces hard-coded "http://reports.resorts-internal.com:8080/download/"
        try {
            GetParameterResponse paramResponse = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(reportUrlSsmParam)
                            .withDecryption(false)
                            .build());
            String baseUrl = paramResponse.parameter().value();
            return baseUrl + "/" + reportName;
        } catch (Exception e) {
            // Fallback: construct URL from environment variable
            String baseUrl = System.getenv().getOrDefault(
                    "REPORT_DOWNLOAD_BASE_URL",
                    "https://reports.resorts-internal.com/download");
            return baseUrl + "/" + reportName;
        }
    }

    /**
     * Returns system information using S3 bucket references and UTC timestamps
     * instead of local file paths and server-local timezone.
     *
     * @return map of system information
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time.Instant with UTC — replaces java.util.Date / SimpleDateFormat
        // which relied on server-local timezone (blocker-19 / cr-java-0111)
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // S3 references replace hard-coded local file paths
        info.put("reportBucket", reportBucket);
        info.put("reportPrefix", reportPrefix);
        info.put("backupPrefix", backupPrefix);
        // Server port sourced from environment variable
        info.put("serverPort", serverPort);
        // UTC timestamp — no server-local timezone dependency
        info.put("generatedAt", timestamp);
        return info;
    }
}
