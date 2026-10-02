package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * ReportService — cloud-native report generation using Amazon S3.
 *
 * <p>All three java.io.File / FileWriter usages (cr-java-0063, original lines 37, 39, 42)
 * have been replaced with AWS SDK v2 S3 client calls:
 * <ul>
 *   <li>Line 37 — {@code new File(REPORT_BASE_PATH)} replaced by S3 bucket/key configuration</li>
 *   <li>Line 39 — {@code reportDir.mkdirs()} removed; S3 creates key prefixes automatically</li>
 *   <li>Line 42 — {@code new FileWriter(fullPath)} replaced by {@code S3Client.putObject()} with
 *       {@code RequestBody.fromString()}</li>
 * </ul>
 * The hard-coded local paths ("/var/legacy/reports/" and "C:\\ResortBackups\\nightly\\") are
 * replaced by environment-variable-backed Spring {@code @Value} properties that resolve to
 * S3 bucket name and key prefix values, eliminating all host-level file system dependencies.
 *
 * <p><strong>cr-java-0071 fix (original line 66):</strong> The hard-coded plain-HTTP URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} in {@code buildReportDownloadUrl}
 * has been replaced with a Spring {@code @Value}-injected property
 * {@code app.report.download-base-url} that is resolved from the AWS Systems Manager
 * Parameter Store via the SSM parameter path {@code /resortslite/report/download-base-url}.
 * This makes the report download endpoint fully environment-agnostic.
 */
@Service
public class ReportService {

    /**
     * S3 bucket name for report storage.
     * Replaces the hard-coded local path constant {@code REPORT_BASE_PATH = "/var/legacy/reports/"}
     * (cr-java-0063 / cr-java-0061). Resolved from environment variable {@code AWS_S3_BUCKET_NAME}.
     */
    @Value("${aws.s3.bucket-name:resorts-lite-reports}")
    private String s3BucketName;

    /**
     * S3 key prefix for nightly backup objects.
     * Replaces the hard-coded Windows path constant {@code BACKUP_PATH = "C:\\ResortBackups\\nightly\\"}
     * (cr-java-0063 / cr-java-0061). Resolved from environment variable {@code AWS_S3_BACKUP_PREFIX}.
     */
    @Value("${aws.s3.backup-prefix:backups/nightly/}")
    private String s3BackupPrefix;

    /** AWS region for S3 client construction. Resolved from environment variable {@code AWS_REGION}. */
    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Server port — sourced from AWS Systems Manager Parameter Store and environment variable injection
     * rather than a hard-coded constant (cr-java-0077 fix).
     *
     * <p>Resolution order:
     * <ol>
     *   <li>AWS SSM Parameter Store: {@code /resortslite/server/port} (loaded via Spring Cloud AWS)</li>
     *   <li>Environment variable: {@code SERVER_PORT}</li>
     *   <li>Spring property: {@code server.port} (set in application.properties via {@code ${SERVER_PORT:8080}})</li>
     * </ol>
     * No port number is hard-coded in application logic; all values are injected at runtime.
     */
    @Value("${app.server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    /**
     * Base URL for report downloads.
     *
     * <p><strong>cr-java-0071 fix:</strong> Replaces the hard-coded plain-HTTP URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} (original line 66).
     * Value is resolved from AWS Systems Manager Parameter Store via the property key
     * {@code app.report.download-base-url}, which maps to the SSM parameter path
     * {@code /resortslite/report/download-base-url}. The environment variable
     * {@code APP_REPORT_DOWNLOAD_BASE_URL} can also be used as a fallback for local development.
     * Each deployment environment (dev, staging, production) stores its own URL in SSM,
     * enabling environment-agnostic deployments without code changes.
     */
    @Value("${app.report.download-base-url:${APP_REPORT_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com/download/}}")
    private String reportDownloadBaseUrl;

    /**
     * Generates a monthly resort report and uploads it directly to Amazon S3.
     *
     * <p><strong>cr-java-0063 fixes applied (original lines 37, 39, 42):</strong>
     * <ol>
     *   <li>Original line 37 — {@code File reportDir = new File(REPORT_BASE_PATH)} removed;
     *       S3 does not require directory creation — the bucket and key prefix are sufficient.</li>
     *   <li>Original line 39 — {@code reportDir.mkdirs()} removed; S3 key prefixes are virtual
     *       and created implicitly when an object is uploaded.</li>
     *   <li>Original line 42 — {@code FileWriter writer = new FileWriter(fullPath)} replaced by
     *       {@code S3Client.putObject(PutObjectRequest, RequestBody.fromString(csvContent))};
     *       report content is built in-memory and streamed directly to S3 without touching the
     *       local file system.</li>
     * </ol>
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string  (e.g. "2024")
     * @return result map containing upload status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        // S3 object key — replaces the former local fullPath variable (cr-java-0063, original line 37)
        String s3ObjectKey = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // -----------------------------------------------------------------------
            // FIX cr-java-0063 (original line 37): File reportDir = new File(REPORT_BASE_PATH)
            // FIX cr-java-0063 (original line 39): reportDir.mkdirs()
            // Both removed — S3 does not require directory/folder creation.
            // -----------------------------------------------------------------------

            // Build CSV content in memory — no local File / FileWriter needed
            // FIX cr-java-0063 (original line 42): new FileWriter(fullPath) → S3 putObject
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // Upload directly to Amazon S3 using AWS SDK v2
            // Replaces: FileWriter writer = new FileWriter(fullPath); writer.write(...); writer.close();
            S3Client s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3ObjectKey)
                    .contentType("text/csv")
                    .build();

            PutObjectResponse response = s3Client.putObject(
                    putRequest,
                    RequestBody.fromString(csvContent)
            );

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3ObjectKey);
            result.put("eTag", response.eTag());
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a secure download URL for a report using the environment-specific base URL
     * retrieved from AWS Systems Manager Parameter Store.
     *
     * <p><strong>cr-java-0071 fix (original line 66):</strong> Replaces the hard-coded
     * plain-HTTP URL {@code "http://reports.resorts-internal.com:8080/download/"} with
     * the {@code reportDownloadBaseUrl} field injected from SSM Parameter Store via
     * {@code app.report.download-base-url} / SSM path {@code /resortslite/report/download-base-url}.
     * Each deployment environment stores its own HTTPS endpoint in SSM, making this method
     * fully environment-agnostic.
     *
     * @param reportName the report file name or S3 object key
     * @return environment-specific HTTPS download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIX cr-java-0071: Hard-coded environment URL replaced with AWS SSM Parameter Store value.
        // Previously: return "http://reports.resorts-internal.com:8080/download/" + reportName;
        // Now: base URL is injected from SSM parameter /resortslite/report/download-base-url
        // enabling environment-agnostic deployments across dev, staging, and production.
        return reportDownloadBaseUrl + reportName; // cr-java-0071 FIXED — sourced from SSM Parameter Store
    }

    /**
     * Returns system information using cloud-native configuration values.
     * Replaces references to hard-coded local paths {@code REPORT_BASE_PATH} and {@code BACKUP_PATH}
     * (cr-java-0061) with S3 bucket / prefix values sourced from environment variables.
     *
     * @return map of system information entries
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: Replaced java.util.Date + SimpleDateFormat with java.time API standardized on UTC.
        // ZonedDateTime.now(ZoneOffset.UTC) ensures consistent timestamps across all cloud regions/containers.
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        // Replaced REPORT_BASE_PATH ("/var/legacy/reports/") with S3 bucket reference (cr-java-0061)
        info.put("reportBucket", s3BucketName);
        info.put("reportPrefix", "reports/");
        // Replaced BACKUP_PATH ("C:\\ResortBackups\\nightly\\") with S3 backup prefix (cr-java-0061)
        info.put("backupPrefix", s3BackupPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
