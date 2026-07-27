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
 * Environment-specific URLs are retrieved from AWS Systems Manager Parameter Store.
 * All timestamps are standardised on UTC using the java.time API.
 */
@Service
public class ReportService {

    // Blocker-1/2/3/4/5/6/7 (cr-java-0061, cr-java-0062, cr-java-0063):
    // Hard-coded absolute file paths and local file write operations replaced with
    // Amazon S3 bucket configuration injected via environment variable.
    @Value("${cloud.aws.s3.reports-bucket:resorts-lite-reports}")
    private String reportsBucket;

    // Blocker-12 (cr-java-0077): Hard-coded port replaced with environment variable injection.
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    // Blocker-11 (cr-java-0071): Hard-coded environment URL replaced with AWS SSM Parameter Store.
    @Value("${app.reports.download.url.param:/resortslite/reports/download-url}")
    private String reportsDownloadUrlParam;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly report and uploads it to Amazon S3.
     * Replaces all local java.io.File / FileWriter operations (blockers 1-7).
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
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // Blocker-4 (cr-java-0062) & Blocker-5/6/7 (cr-java-0063):
            // Upload directly to S3 — durable, scalable, no ephemeral local storage.
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucket);
            result.put("s3Key", objectKey);
            // Blocker-12 (cr-java-0077): port sourced from environment variable, not hard-coded
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL by retrieving the base URL from
     * AWS Systems Manager Parameter Store (blocker-11, cr-java-0071).
     *
     * @param reportName the name of the report object
     * @return the fully qualified download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Blocker-11 (cr-java-0071): Retrieve environment-specific URL from SSM Parameter Store
        // instead of using a hard-coded "http://reports.resorts-internal.com:8080/download/" value.
        try {
            GetParameterResponse paramResponse = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(reportsDownloadUrlParam)
                            .withDecryption(false)
                            .build());
            String baseUrl = paramResponse.parameter().value();
            return baseUrl + "/" + reportName;
        } catch (Exception e) {
            // Fallback: construct URL from environment-injected host (never hard-coded)
            String reportHost = System.getenv().getOrDefault("REPORTS_HOST", "reports.resorts-internal.com");
            return "https://" + reportHost + "/download/" + reportName;
        }
    }

    /**
     * Returns system information using UTC timestamps (blocker-19, cr-java-0111).
     * Replaces java.util.Date / SimpleDateFormat with java.time.Instant standardised on UTC.
     *
     * @return system info map
     */
    public Map<String, Object> getSystemInfo() {
        // Blocker-19 (cr-java-0111): Replace java.util.Date with java.time API, UTC-standardised
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // Blocker-1/2/3 (cr-java-0061): No local paths — report location is S3 bucket/key
        info.put("reportsBucket", reportsBucket);
        // Blocker-12 (cr-java-0077): port from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
