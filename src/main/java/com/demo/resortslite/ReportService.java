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
 * replaced with S3 operations. Environment URLs are retrieved from AWS Systems
 * Manager Parameter Store. Time operations use java.time API standardized on UTC.
 */
@Service
public class ReportService {

    // S3 bucket name injected from environment variable — no hard-coded paths
    @Value("${cloud.aws.s3.report-bucket:resorts-reports-bucket}")
    private String reportBucket;

    // AWS region injected from environment variable
    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    // Server port injected from environment variable — replaces hard-coded 8080
    @Value("${SERVER_PORT:${server.port:8080}}")
    private int serverPort;

    // SSM parameter name for the report download base URL
    @Value("${cloud.aws.ssm.report-url-param:/resorts/report/download-url}")
    private String reportUrlSsmParam;

    /**
     * Generates a monthly report CSV and uploads it to Amazon S3.
     * Replaces all local java.io.File / FileWriter operations with S3 PutObject calls.
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

            // Upload report content directly to Amazon S3 using AWS SDK v2
            S3Client s3 = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3.putObject(putRequest, RequestBody.fromString(csvContent.toString()));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", objectKey);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL by retrieving the base URL from AWS Systems
     * Manager Parameter Store, replacing the hard-coded environment-specific URL.
     *
     * @param reportName the name of the report file
     * @return the full download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        try {
            // Retrieve base URL from AWS SSM Parameter Store — no hard-coded URL
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetParameterRequest paramRequest = GetParameterRequest.builder()
                    .name(reportUrlSsmParam)
                    .withDecryption(false)
                    .build();

            GetParameterResponse paramResponse = ssmClient.getParameter(paramRequest);
            String baseUrl = paramResponse.parameter().value();
            return baseUrl + "/download/" + reportName;

        } catch (Exception e) {
            // Fallback: construct URL from environment variable if SSM is unavailable
            String baseUrl = System.getenv().getOrDefault("REPORT_DOWNLOAD_BASE_URL",
                    "https://reports.resorts-internal.com/download");
            return baseUrl + "/" + reportName;
        }
    }

    /**
     * Returns system information using UTC timestamps via java.time API.
     * Replaces java.util.Date / SimpleDateFormat with java.time.Instant for
     * timezone-safe, cloud-compatible time handling standardized on UTC.
     *
     * @return map containing system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time.Instant for UTC-standardized timestamp — replaces java.util.Date
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        info.put("reportBucket", reportBucket);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }
}
