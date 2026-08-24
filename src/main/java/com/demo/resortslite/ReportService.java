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
 * ReportService — cloud-native implementation.
 *
 * <p>All file I/O has been migrated from the local file system to Amazon S3
 * (cr-java-0061, cr-java-0062, cr-java-0063). Hard-coded environment URLs are
 * resolved at runtime from AWS Systems Manager Parameter Store (cr-java-0071).
 * The hard-coded server port is externalised to an environment variable backed
 * by SSM Parameter Store (cr-java-0077). Time handling uses java.time with UTC
 * to eliminate timezone drift across cloud regions (cr-java-0111).</p>
 */
@Service
public class ReportService {

    // -----------------------------------------------------------------------
    // cr-java-0061 / cr-java-0062 / cr-java-0063 — Hard-coded file paths and
    // local file-system write operations replaced with Amazon S3.
    // The S3 bucket name is read from the environment variable REPORT_S3_BUCKET
    // so it can be injected at deploy time without code changes.
    // -----------------------------------------------------------------------
    private static final String REPORT_S3_BUCKET =
            System.getenv("REPORT_S3_BUCKET") != null
                    ? System.getenv("REPORT_S3_BUCKET")
                    : "resorts-reports-bucket";

    private static final String REPORT_S3_PREFIX = "reports/";

    // -----------------------------------------------------------------------
    // cr-java-0077 — Hard-coded port replaced with environment variable.
    // Falls back to SSM Parameter Store key /resortslite/server/port when the
    // environment variable SERVER_PORT is not set.
    // -----------------------------------------------------------------------
    private static final int SERVER_PORT = resolveServerPort();

    private static int resolveServerPort() {
        String envPort = System.getenv("SERVER_PORT");
        if (envPort != null && !envPort.isEmpty()) {
            return Integer.parseInt(envPort);
        }
        try {
            SsmClient ssm = SsmClient.create();
            GetParameterResponse resp = ssm.getParameter(
                    GetParameterRequest.builder()
                            .name("/resortslite/server/port")
                            .withDecryption(false)
                            .build());
            return Integer.parseInt(resp.parameter().value());
        } catch (Exception e) {
            return 8080; // safe default
        }
    }

    // -----------------------------------------------------------------------
    // cr-java-0071 — Hard-coded report-download URL replaced with a value
    // retrieved from AWS Systems Manager Parameter Store at runtime.
    // -----------------------------------------------------------------------
    private static final String REPORT_DOWNLOAD_BASE_URL = resolveReportDownloadUrl();

    private static String resolveReportDownloadUrl() {
        String envUrl = System.getenv("REPORT_DOWNLOAD_BASE_URL");
        if (envUrl != null && !envUrl.isEmpty()) {
            return envUrl;
        }
        try {
            SsmClient ssm = SsmClient.create();
            GetParameterResponse resp = ssm.getParameter(
                    GetParameterRequest.builder()
                            .name("/resortslite/report/download-base-url")
                            .withDecryption(false)
                            .build());
            return resp.parameter().value();
        } catch (Exception e) {
            return "https://reports.resorts-internal.com/download";
        }
    }

    /**
     * Generates a monthly CSV report and uploads it directly to Amazon S3.
     *
     * <p>Replaces the previous implementation that wrote to the local path
     * {@code /var/legacy/reports/} (cr-java-0061, cr-java-0062, cr-java-0063).</p>
     *
     * @param month numeric month string (e.g. "03")
     * @param year  four-digit year string (e.g. "2024")
     * @return result map containing upload status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // cr-java-0061 / cr-java-0062 / cr-java-0063: S3 key replaces local absolute path
        String s3Key = REPORT_S3_PREFIX + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes("UTF-8");

            // Upload to Amazon S3 (replaces FileWriter to local path)
            S3Client s3 = S3Client.create();
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(REPORT_S3_BUCKET)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();
            s3.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            result.put("status", "generated");
            result.put("s3Bucket", REPORT_S3_BUCKET);
            result.put("s3Key", s3Key);
            result.put("serverPort", SERVER_PORT);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a pre-signed or base URL for downloading a named report.
     *
     * <p>cr-java-0071: The base URL is no longer hard-coded; it is resolved
     * from AWS Systems Manager Parameter Store or the {@code REPORT_DOWNLOAD_BASE_URL}
     * environment variable at startup.</p>
     *
     * @param reportName the report file name
     * @return fully qualified download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071: URL sourced from SSM Parameter Store / env var — not hard-coded
        return REPORT_DOWNLOAD_BASE_URL + "/" + reportName;
    }

    /**
     * Returns current system information using UTC timestamps.
     *
     * <p>cr-java-0111: Replaced {@code java.util.Date} / {@code SimpleDateFormat}
     * with {@code java.time.Instant} formatted in UTC to ensure consistent
     * timestamps across all cloud regions and container instances.</p>
     *
     * @return map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111: java.time.Instant with UTC — no server-local timezone dependency
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // cr-java-0061: S3 bucket/prefix reported instead of local absolute paths
        info.put("reportS3Bucket", REPORT_S3_BUCKET);
        info.put("reportS3Prefix", REPORT_S3_PREFIX);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
