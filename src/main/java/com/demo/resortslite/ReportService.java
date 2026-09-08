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

import java.nio.charset.StandardCharsets;
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

    // cr-java-0061 / cr-java-0062 / cr-java-0063 FIX:
    // Replaced hard-coded file paths (/var/legacy/reports/, C:\ResortBackups\nightly\)
    // with S3 bucket and prefix configuration injected via environment variables.
    @Value("${cloud.aws.s3.bucket:resorts-lite-reports}")
    private String s3BucketName;

    @Value("${cloud.aws.s3.report-prefix:reports/}")
    private String reportPrefix;

    @Value("${cloud.aws.s3.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    // cr-java-0077 FIX:
    // Replaced hard-coded SERVER_PORT = 8080 with environment variable injection.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0071 FIX:
    // Replaced hard-coded "http://reports.resorts-internal.com:8080/download/" URL
    // with value retrieved from AWS Systems Manager Parameter Store at runtime.
    @Value("${app.report.download-url-param:/resortslite/report/download-url}")
    private String reportDownloadUrlParam;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly report and uploads it to Amazon S3.
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return a map containing the operation status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // cr-java-0061 / cr-java-0062 / cr-java-0063 FIX:
        // Replaced local file path construction and java.io.File / FileWriter operations
        // with Amazon S3 PutObject call using AWS SDK for Java v2.
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest,
                    RequestBody.fromBytes(csvContent.getBytes(StandardCharsets.UTF_8)));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            // cr-java-0077 FIX: serverPort now injected from environment variable
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL by retrieving the base URL from
     * AWS Systems Manager Parameter Store.
     *
     * @param reportName the name of the report file
     * @return the full download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX:
        // Replaced hard-coded "http://reports.resorts-internal.com:8080/download/" URL
        // with a value retrieved from AWS Systems Manager Parameter Store,
        // enabling environment-agnostic deployments.
        try {
            GetParameterResponse paramResponse = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(reportDownloadUrlParam)
                            .withDecryption(false)
                            .build());
            String baseUrl = paramResponse.parameter().value();
            return baseUrl + reportName;
        } catch (Exception e) {
            // Fallback to environment variable if SSM is unavailable
            String baseUrl = System.getenv().getOrDefault(
                    "REPORT_DOWNLOAD_BASE_URL",
                    "https://reports.resorts-internal.com/download/");
            return baseUrl + reportName;
        }
    }

    /**
     * Returns system information using S3 storage references and UTC timestamps.
     *
     * @return a map containing system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX:
        // Replaced java.util.Date / SimpleDateFormat with java.time.Instant (UTC)
        // to eliminate timezone inconsistencies across cloud regions and containers.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // cr-java-0061 FIX: replaced hard-coded local paths with S3 references
        info.put("reportBucket", s3BucketName);
        info.put("reportPrefix", reportPrefix);
        info.put("backupPrefix", backupPrefix);
        // cr-java-0077 FIX: serverPort injected from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
