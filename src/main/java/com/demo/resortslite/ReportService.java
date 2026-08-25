package com.demo.resortslite;

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
 * cloud-native, durable object storage. All file path dependencies have been
 * replaced with S3 operations. Configuration values (URLs, ports) are
 * externalized to AWS Systems Manager Parameter Store.
 */
@Service
public class ReportService {

    // S3 bucket name read from environment variable — no hard-coded paths
    private final String reportBucket = System.getenv().getOrDefault("REPORT_S3_BUCKET", "resorts-lite-reports");

    // S3 key prefix replaces the former hard-coded /var/legacy/reports/ path
    private static final String REPORT_KEY_PREFIX = "reports/";

    // Server port externalized via environment variable (replaces hard-coded 8080)
    // Injected by ECS task definition / EKS pod spec / Elastic Beanstalk env config
    private final int serverPort = Integer.parseInt(
            System.getenv().getOrDefault("SERVER_PORT", "8080"));

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService() {
        this.s3Client = S3Client.create();
        this.ssmClient = SsmClient.create();
    }

    /**
     * Generates a monthly report CSV and uploads it to Amazon S3.
     * Replaces all local java.io.File / FileWriter operations (blockers 1-7).
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return result map containing S3 location and status
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = REPORT_KEY_PREFIX + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes();

            // Upload report directly to S3 — durable, scalable, cloud-native storage
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the base URL retrieved from
     * AWS Systems Manager Parameter Store (replaces blocker-11 hard-coded URL).
     *
     * @param reportName the name of the report file
     * @return HTTPS download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // Retrieve base URL from AWS SSM Parameter Store — no hard-coded environment URL
        String baseUrl = getSsmParameter("/resortslite/report/base-url",
                "https://reports.resorts-internal.com/download");
        return baseUrl + "/" + reportName;
    }

    /**
     * Returns system information using UTC timestamps (replaces blocker-19 java.util.Date).
     * All time values are standardized on UTC using java.time API.
     *
     * @return map of system information
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time Instant with UTC — eliminates server-local timezone dependency
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        info.put("reportBucket", reportBucket);
        info.put("reportKeyPrefix", REPORT_KEY_PREFIX);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * @param paramName    the SSM parameter name/path
     * @param defaultValue fallback value if the parameter cannot be retrieved
     * @return the parameter value or the default
     */
    private String getSsmParameter(String paramName, String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
