package com.demo.resortslite;

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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native implementation.
 *
 * <p>All file I/O has been migrated to Amazon S3 (AWS SDK for Java v2).
 * Hard-coded paths, ports, and URLs have been replaced with values sourced
 * from AWS Systems Manager Parameter Store and environment variables.
 * Time handling uses java.time with UTC to avoid timezone drift across regions.
 */
@Service
public class ReportService {

    // S3 bucket name is read from the environment variable REPORT_S3_BUCKET.
    // Set this variable in ECS task definition / Elastic Beanstalk environment.
    private final String reportBucket = System.getenv().getOrDefault("REPORT_S3_BUCKET", "resorts-reports-bucket");

    // S3 key prefix replaces the former hard-coded /var/legacy/reports/ path (blocker-1, blocker-3).
    private static final String REPORT_KEY_PREFIX = "reports/";

    // Server port is read from the SERVER_PORT environment variable injected by ECS/EKS (blocker-12).
    private final int serverPort;

    // Report download base URL is read from AWS SSM Parameter Store (blocker-11).
    private final String reportDownloadBaseUrl;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService() {
        this.s3Client = S3Client.create();
        this.ssmClient = SsmClient.create();

        // Resolve server port from environment variable; default to 8080 if not set (blocker-12).
        String portEnv = System.getenv("SERVER_PORT");
        this.serverPort = (portEnv != null && !portEnv.isEmpty()) ? Integer.parseInt(portEnv) : 8080;

        // Resolve report download URL from SSM Parameter Store (blocker-11).
        this.reportDownloadBaseUrl = resolveParameterStoreValue(
                "/resortslite/report/download-base-url",
                "https://reports.resorts-internal.com/download");
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     * Replaces all local java.io.File / FileWriter operations (blockers 1-7).
     *
     * @param month the month identifier (e.g. "03")
     * @param year  the year identifier (e.g. "2024")
     * @return result map containing S3 upload status and object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 object key replaces the former hard-coded absolute file path (blocker-1, blocker-3).
        String s3Key = REPORT_KEY_PREFIX + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency (blocker-4, blocker-5, blocker-6, blocker-7).
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);

            // Upload report directly to Amazon S3 (blocker-2, blocker-4).
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
     * Builds the report download URL using the base URL sourced from AWS SSM Parameter Store.
     * Replaces the former hard-coded HTTP URL (blocker-11).
     *
     * @param reportName the name of the report object in S3
     * @return the fully qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // URL is sourced from SSM Parameter Store — no hard-coded environment-specific value (blocker-11).
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information using UTC timestamps via java.time API.
     * Replaces java.util.Date / SimpleDateFormat usage (blocker-19).
     *
     * @return map containing system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time.Instant with UTC to avoid timezone drift in multi-region deployments (blocker-19).
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> info = new HashMap<>();
        info.put("reportBucket", reportBucket);
        info.put("reportKeyPrefix", REPORT_KEY_PREFIX);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Retrieves a configuration value from AWS Systems Manager Parameter Store.
     * Falls back to the provided default value if the parameter cannot be resolved.
     *
     * @param parameterName the SSM parameter path
     * @param defaultValue  fallback value used when SSM is unavailable
     * @return the resolved parameter value
     */
    private String resolveParameterStoreValue(String parameterName, String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
