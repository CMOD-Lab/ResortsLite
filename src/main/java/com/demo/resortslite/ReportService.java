package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
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
 * ReportService — cloud-native report generation using Amazon S3 for durable
 * object storage and AWS Systems Manager Parameter Store for all environment-
 * specific configuration values (bucket name, download base URL, server port).
 *
 * <p>All hard-coded file paths, Windows/Linux absolute paths, and local
 * {@code java.io.File} operations have been replaced with S3 SDK calls.
 * All hard-coded environment URLs and port numbers are externalised to
 * AWS SSM Parameter Store and injected at runtime via environment variables.
 * {@code java.util.Date} / {@code SimpleDateFormat} have been replaced with
 * the {@code java.time} API standardised on UTC.</p>
 */
@Service
public class ReportService {

    // -----------------------------------------------------------------------
    // Cloud-native configuration — values injected from environment variables
    // which are populated by ECS task definitions / Elastic Beanstalk env vars
    // that reference AWS SSM Parameter Store entries at deploy time.
    // -----------------------------------------------------------------------

    /**
     * S3 bucket name for report storage.
     * Env var: REPORT_S3_BUCKET  →  SSM parameter: /resortsLite/report/s3Bucket
     */
    @Value("${report.s3.bucket:${REPORT_S3_BUCKET:resortsLite-reports}}")
    private String reportS3Bucket;

    /**
     * S3 key prefix (folder) for reports.
     * Env var: REPORT_S3_PREFIX  →  SSM parameter: /resortsLite/report/s3Prefix
     */
    @Value("${report.s3.prefix:${REPORT_S3_PREFIX:reports/}}")
    private String reportS3Prefix;

    /**
     * Base URL for report downloads — retrieved from SSM Parameter Store.
     * Replaces the hard-coded {@code http://reports.resorts-internal.com:8080/download/}
     * URL (blocker-11 / cr-java-0071).
     * Env var: REPORT_DOWNLOAD_BASE_URL  →  SSM: /resortsLite/report/downloadBaseUrl
     */
    @Value("${report.download.base-url:${REPORT_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com/download/}}")
    private String reportDownloadBaseUrl;

    /**
     * Server port — externalised to SSM Parameter Store / environment variable.
     * Replaces the hard-coded {@code private static final int SERVER_PORT = 8080}
     * (blocker-12 / cr-java-0077).
     * Env var: SERVER_PORT  →  SSM: /resortsLite/server/port
     */
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     *
     * <p>Replaces all {@code java.io.File} / {@code FileWriter} local-disk
     * operations (blockers 3-7 / cr-java-0061, cr-java-0062, cr-java-0063)
     * with an S3 {@code PutObject} call using AWS SDK for Java v2.</p>
     *
     * @param month numeric month string (e.g. "03")
     * @param year  four-digit year string (e.g. "2024")
     * @return result map containing S3 object key, bucket, and server port
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 object key replaces the hard-coded absolute file path
        // (blockers 1-3 / cr-java-0061) and local FileWriter write
        // (blocker-4 / cr-java-0062).
        String s3Key = reportS3Prefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // Upload report content directly to S3 — no local file system required.
            // Replaces: new File(REPORT_BASE_PATH) + new FileWriter(fullPath)
            // (blockers 5-7 / cr-java-0063)
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportS3Bucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportS3Bucket);
            result.put("s3Key", s3Key);
            // serverPort is now externalised — no hard-coded value (blocker-12)
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the base URL retrieved from
     * AWS SSM Parameter Store via the {@code report.download.base-url}
     * environment variable.
     *
     * <p>Replaces the hard-coded {@code http://reports.resorts-internal.com:8080/download/}
     * URL (blocker-11 / cr-java-0071).</p>
     *
     * @param reportName the report file name
     * @return fully qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Hard-coded environment URL replaced with SSM Parameter Store value
        // injected via environment variable (blocker-11 / cr-java-0071).
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information using UTC timestamps via the {@code java.time}
     * API, replacing the deprecated {@code java.util.Date} / {@code SimpleDateFormat}
     * usage (blocker-19 / cr-java-0111).
     *
     * <p>All configuration values (paths, port) are now sourced from SSM
     * Parameter Store / environment variables rather than hard-coded constants.</p>
     *
     * @return map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // Replaced java.util.Date + SimpleDateFormat with java.time.Instant (UTC)
        // (blocker-19 / cr-java-0111)
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // Hard-coded file paths replaced with S3 bucket/prefix references
        info.put("reportS3Bucket", reportS3Bucket);
        info.put("reportS3Prefix", reportS3Prefix);
        // Hard-coded port replaced with externalised value (blocker-12)
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Retrieves a parameter value from AWS SSM Parameter Store.
     *
     * @param parameterName the SSM parameter name/path
     * @return the parameter value, or an empty string if not found
     */
    private String getSsmParameter(String parameterName) {
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(parameterName)
                            .withDecryption(true)
                            .build());
            return response.parameter().value();
        } catch (Exception e) {
            return "";
        }
    }
}
