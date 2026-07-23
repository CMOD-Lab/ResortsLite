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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native implementation.
 *
 * <p>All file-system operations have been replaced with Amazon S3 object storage
 * (AWS SDK for Java v2) to eliminate hard-coded absolute paths and local write
 * dependencies that are incompatible with ephemeral container file systems.</p>
 *
 * <p>Environment-specific URLs are resolved from AWS Systems Manager Parameter
 * Store at runtime so that no endpoint is hard-coded in the source.</p>
 *
 * <p>All time handling uses {@code java.time} (UTC) instead of
 * {@code java.util.Date} / {@code SimpleDateFormat} to avoid timezone
 * inconsistencies across cloud regions.</p>
 */
@Service
public class ReportService {

    // -----------------------------------------------------------------------
    // S3 configuration — injected from application.properties / env vars.
    // Replaces hard-coded paths: /var/legacy/reports/ and C:\ResortBackups\nightly\
    // -----------------------------------------------------------------------
    @Value("${aws.s3.bucket-name}")
    private String s3BucketName;

    @Value("${aws.region}")
    private String awsRegion;

    // -----------------------------------------------------------------------
    // Server port — injected from environment variable (PORT) at runtime.
    // Replaces: private static final int SERVER_PORT = 8080;
    // -----------------------------------------------------------------------
    @Value("${server.port:8080}")
    private int serverPort;

    // -----------------------------------------------------------------------
    // SSM parameter name for the report download base URL.
    // Replaces: hard-coded "http://reports.resorts-internal.com:8080/download/"
    // -----------------------------------------------------------------------
    @Value("${aws.ssm.param.report-download-base-url}")
    private String reportDownloadUrlParam;

    // -----------------------------------------------------------------------
    // Lazy-initialised AWS clients (package-private for testability)
    // -----------------------------------------------------------------------
    private S3Client s3Client;
    private SsmClient ssmClient;

    private S3Client getS3Client() {
        if (s3Client == null) {
            s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();
        }
        return s3Client;
    }

    private SsmClient getSsmClient() {
        if (ssmClient == null) {
            ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .build();
        }
        return ssmClient;
    }

    /**
     * Generates a monthly booking report and uploads it to Amazon S3.
     *
     * <p>Replaces the previous implementation that wrote to the local path
     * {@code /var/legacy/reports/} using {@code java.io.File} and
     * {@code FileWriter}, which are incompatible with ephemeral container
     * file systems.</p>
     *
     * @param month the month identifier (e.g. "03")
     * @param year  the four-digit year (e.g. "2024")
     * @return a result map containing the S3 object key and upload status
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // S3 object key replaces the former absolute local file path
        String s3Key = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes();

            // Upload directly to S3 — durable, scalable, no host file system required
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            getS3Client().putObject(putRequest, RequestBody.fromBytes(contentBytes));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report by resolving the base URL
     * from AWS Systems Manager Parameter Store.
     *
     * <p>Replaces the hard-coded {@code http://reports.resorts-internal.com:8080/download/}
     * URL with a value retrieved at runtime from SSM Parameter Store, enabling
     * environment-agnostic deployments.</p>
     *
     * @param reportName the report file/object name
     * @return the fully-qualified download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Resolve base URL from AWS SSM Parameter Store — no hard-coded endpoint
        GetParameterResponse paramResponse = getSsmClient().getParameter(
                GetParameterRequest.builder()
                        .name(reportDownloadUrlParam)
                        .withDecryption(false)
                        .build());
        String baseUrl = paramResponse.parameter().value();
        return baseUrl + reportName;
    }

    /**
     * Returns system information using UTC timestamps from {@code java.time}.
     *
     * <p>Replaces {@code java.util.Date} / {@code SimpleDateFormat} with
     * {@code java.time.Instant} and {@code ZonedDateTime} standardised on UTC
     * to avoid timezone inconsistencies across cloud regions and containers.</p>
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time API with explicit UTC zone — replaces new Date() / SimpleDateFormat
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
        String timestamp = nowUtc.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> info = new HashMap<>();
        info.put("storageBucket", s3BucketName);   // S3 bucket replaces local REPORT_BASE_PATH
        info.put("serverPort", serverPort);          // env-var-injected port replaces hard-coded 8080
        info.put("generatedAt", timestamp);          // UTC ISO-8601 replaces server-local timezone
        info.put("generatedAtEpoch", Instant.now().toEpochMilli());
        return info;
    }
}
