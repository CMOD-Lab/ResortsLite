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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation using Amazon S3 for durable
 * object storage and AWS Systems Manager Parameter Store for all externalized
 * configuration values (paths, ports, URLs).
 *
 * <p>Blockers resolved:
 * <ul>
 *   <li>cr-java-0061 (lines 23, 37, 42) — hard-coded file paths replaced with S3 bucket/key config</li>
 *   <li>cr-java-0062 (line 42)          — local file write replaced with S3 PutObject</li>
 *   <li>cr-java-0063 (lines 37, 39, 42) — java.io.File usage replaced with AWS SDK S3Client</li>
 *   <li>cr-java-0071 (line 66)          — hard-coded report download URL replaced with SSM Parameter Store</li>
 *   <li>cr-java-0077 (line 28)          — hard-coded SERVER_PORT replaced with SSM Parameter Store / env var</li>
 *   <li>cr-java-0111 (line 70)          — java.util.Date replaced with java.time API standardized on UTC</li>
 * </ul>
 */
@Service
public class ReportService {

    // -------------------------------------------------------------------------
    // FIX cr-java-0061, cr-java-0062, cr-java-0063:
    // Hard-coded absolute file paths (/var/legacy/reports/, C:\ResortBackups\nightly\)
    // and all java.io.File / FileWriter operations are replaced with Amazon S3.
    // The S3 bucket name is read from the environment variable REPORT_S3_BUCKET
    // (injected at runtime by ECS task definition / Elastic Beanstalk env config).
    // -------------------------------------------------------------------------
    private final String reportS3Bucket;

    // -------------------------------------------------------------------------
    // FIX cr-java-0077:
    // Hard-coded SERVER_PORT constant replaced with a value read from
    // AWS Systems Manager Parameter Store (/resortsLite/server/port) at startup,
    // with a fallback to the SERVER_PORT environment variable.
    // -------------------------------------------------------------------------
    private final int serverPort;

    // -------------------------------------------------------------------------
    // FIX cr-java-0071:
    // Hard-coded report download URL replaced with a value retrieved from
    // AWS Systems Manager Parameter Store (/resortsLite/reports/downloadBaseUrl).
    // -------------------------------------------------------------------------
    private final String reportDownloadBaseUrl;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    /**
     * Constructor — resolves all externalized configuration from SSM Parameter Store
     * and environment variables at bean-initialization time.
     */
    public ReportService() {
        this.s3Client  = S3Client.create();
        this.ssmClient = SsmClient.create();

        // Resolve S3 bucket from environment variable (set in ECS/Beanstalk task definition)
        String bucket = System.getenv("REPORT_S3_BUCKET");
        this.reportS3Bucket = (bucket != null && !bucket.isEmpty()) ? bucket : "resortsLite-reports";

        // FIX cr-java-0077: resolve server port from SSM Parameter Store
        this.serverPort = resolveIntParameter("/resortsLite/server/port", "SERVER_PORT", 8080);

        // FIX cr-java-0071: resolve report download base URL from SSM Parameter Store
        this.reportDownloadBaseUrl = resolveStringParameter(
                "/resortsLite/reports/downloadBaseUrl",
                "REPORT_DOWNLOAD_BASE_URL",
                "https://reports.resorts-internal.com/download");
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     *
     * <p>Replaces the previous implementation that wrote to the local file system
     * ({@code /var/legacy/reports/}) using {@code java.io.File} and {@code FileWriter}.
     * Data written to S3 is durable, replicated, and accessible from any instance.
     *
     * @param month the month identifier (e.g. "03")
     * @param year  the year identifier  (e.g. "2024")
     * @return a result map containing status, S3 key, and server port
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // FIX cr-java-0061 / cr-java-0062 / cr-java-0063:
        // Build an S3 object key instead of a local file path.
        String s3Key = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // FIX cr-java-0062 / cr-java-0063:
            // Upload directly to S3 — replaces FileWriter and File.mkdirs()
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportS3Bucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            result.put("status", "generated");
            result.put("s3Bucket", reportS3Bucket);
            result.put("s3Key", s3Key);
            // FIX cr-java-0077: serverPort sourced from SSM / env var, not hard-coded
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report object stored in S3.
     *
     * <p>FIX cr-java-0071: The base URL is no longer hard-coded; it is retrieved
     * from AWS Systems Manager Parameter Store ({@code /resortsLite/reports/downloadBaseUrl})
     * so the same artifact can be deployed to dev, staging, and production without
     * source-code changes.
     *
     * @param reportName the S3 object key / report file name
     * @return the fully-qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIX cr-java-0071: URL assembled from SSM-sourced base URL — no hard-coded host/port
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns current system information using UTC timestamps.
     *
     * <p>FIX cr-java-0111: {@code java.util.Date} and {@code SimpleDateFormat} replaced
     * with {@code java.time.Instant} / {@code ZonedDateTime} standardized on UTC, eliminating
     * server-local timezone dependencies that cause inconsistencies across cloud regions.
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // FIX cr-java-0111: Use java.time API with explicit UTC zone — no server-local timezone
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
        String timestamp = nowUtc.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> info = new HashMap<>();
        // FIX cr-java-0061: report storage location is now an S3 bucket/prefix, not a local path
        info.put("reportS3Bucket", reportS3Bucket);
        info.put("reportS3Prefix", "reports/");
        // FIX cr-java-0077: port sourced from SSM / env var
        info.put("serverPort", serverPort);
        // FIX cr-java-0111: UTC timestamp from java.time API
        info.put("generatedAt", timestamp);
        return info;
    }

    // -------------------------------------------------------------------------
    // Private helpers — SSM Parameter Store resolution with env-var fallback
    // -------------------------------------------------------------------------

    /**
     * Resolves a string configuration value from SSM Parameter Store.
     * Falls back to the specified environment variable, then to the default value.
     */
    private String resolveStringParameter(String ssmPath, String envVar, String defaultValue) {
        // Try SSM Parameter Store first
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder().name(ssmPath).withDecryption(true).build());
            String value = response.parameter().value();
            if (value != null && !value.isEmpty()) {
                return value;
            }
        } catch (Exception ignored) {
            // SSM not available (e.g., local dev) — fall through to env var
        }
        // Fall back to environment variable
        String envValue = System.getenv(envVar);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }
        return defaultValue;
    }

    /**
     * Resolves an integer configuration value from SSM Parameter Store.
     * Falls back to the specified environment variable, then to the default value.
     */
    private int resolveIntParameter(String ssmPath, String envVar, int defaultValue) {
        String raw = resolveStringParameter(ssmPath, envVar, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
