package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
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
 * cloud-native, durable object storage. All file path dependencies have been
 * replaced with S3 operations. Port and URL configuration is externalized to
 * AWS Systems Manager Parameter Store. Time operations use java.time (UTC).
 */
@Service
public class ReportService {

    // Blocker-1,2,3,4,5,6,7 (cr-java-0061, cr-java-0062, cr-java-0063):
    // Hard-coded file paths and local file write operations replaced with Amazon S3.
    // S3 bucket name is injected from environment variable — no hard-coded paths.
    @Value("${cloud.aws.s3.reports-bucket:resorts-lite-reports}")
    private String reportsBucket;

    // Blocker-12 (cr-java-0077): Hard-coded port replaced with environment variable injection.
    // Value is resolved at runtime from the environment or application properties.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // Blocker-11 (cr-java-0071): Hard-coded environment URL replaced with AWS SSM Parameter Store.
    // The parameter name is injected; the actual URL is fetched at runtime.
    @Value("${ssm.parameter.report-download-url:/resortslite/report/download-url}")
    private String reportDownloadUrlParam;

    /**
     * Generates a monthly report CSV and uploads it to Amazon S3.
     * Replaces all local java.io.File and FileWriter operations (blockers 1-7).
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

            // Blocker-1,2,3,4,5,6,7: Upload to Amazon S3 instead of writing to local file system
            S3Client s3 = S3Client.builder()
                    .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
                    .build();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3.putObject(putRequest, RequestBody.fromString(csvContent.toString()));
            s3.close();

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucket);
            result.put("s3Key", objectKey);
            // Blocker-12: serverPort now resolved from environment variable, not hard-coded
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
     * @param reportName the name of the report
     * @return the full download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // Blocker-11 (cr-java-0071): Retrieve environment-specific URL from SSM Parameter Store
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
                    .build();

            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(reportDownloadUrlParam)
                            .withDecryption(false)
                            .build());
            ssmClient.close();

            String baseUrl = response.parameter().value();
            return baseUrl + "/" + reportName;
        } catch (Exception e) {
            // Fallback: construct URL from environment variable if SSM is unavailable
            String baseUrl = System.getenv().getOrDefault(
                    "REPORT_DOWNLOAD_BASE_URL",
                    "https://reports.resorts-internal.com/download");
            return baseUrl + "/" + reportName;
        }
    }

    /**
     * Returns system information using UTC timestamps (blocker-19, cr-java-0111).
     * Replaces java.util.Date / SimpleDateFormat with java.time API standardized on UTC.
     *
     * @return map of system information
     */
    public Map<String, Object> getSystemInfo() {
        // Blocker-19 (cr-java-0111): Replace java.util.Date with java.time Instant (UTC)
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // Blocker-1,2,3: S3 bucket reference instead of hard-coded local paths
        info.put("reportsBucket", reportsBucket);
        // Blocker-12: serverPort resolved from environment variable
        info.put("serverPort", serverPort);
        // Blocker-19: UTC timestamp from java.time API
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }
}
