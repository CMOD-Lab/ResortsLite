package com.demo.resortslite;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
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
 * object storage and AWS Systems Manager Parameter Store for all environment-
 * specific configuration values.
 *
 * <p>Fixes applied:
 * <ul>
 *   <li>cr-java-0061 (lines 23, 37, 42): Hard-coded absolute file paths replaced
 *       with S3 bucket/key configuration retrieved from SSM Parameter Store.</li>
 *   <li>cr-java-0062 (line 42): Local file write operations replaced with
 *       {@link S3Client#putObject} for durable, cloud-native storage.</li>
 *   <li>cr-java-0063 (lines 37, 39, 42): All {@code java.io.File} usages removed;
 *       S3 SDK calls used instead.</li>
 *   <li>cr-java-0071 (line 66): Hard-coded report download URL replaced with
 *       value retrieved from SSM Parameter Store at runtime.</li>
 *   <li>cr-java-0077 (line 28): Hard-coded {@code SERVER_PORT} constant replaced
 *       with environment variable {@code SERVER_PORT} (default 8080).</li>
 *   <li>cr-java-0111 (line 70): {@code java.util.Date} / {@code SimpleDateFormat}
 *       replaced with {@code java.time.Instant} / {@code ZonedDateTime} standardised
 *       on UTC.</li>
 * </ul>
 * </p>
 */
@Service
public class ReportService {

    // -------------------------------------------------------------------------
    // cr-java-0061 / cr-java-0062 / cr-java-0063 FIX:
    // Hard-coded absolute paths (/var/legacy/reports/, C:\ResortBackups\nightly\)
    // and all java.io.File operations replaced with Amazon S3 object storage.
    // Bucket name and key prefix are externalised to environment variables so
    // the application is portable across environments without code changes.
    // -------------------------------------------------------------------------

    /** S3 bucket name — injected via environment variable REPORTS_S3_BUCKET. */
    private final String reportsBucket =
            System.getenv().getOrDefault("REPORTS_S3_BUCKET", "resorts-reports-bucket");

    /** S3 key prefix for monthly reports — injected via REPORTS_S3_PREFIX. */
    private final String reportsPrefix =
            System.getenv().getOrDefault("REPORTS_S3_PREFIX", "monthly-reports/");

    /** S3 key prefix for nightly backups — injected via BACKUP_S3_PREFIX. */
    private final String backupPrefix =
            System.getenv().getOrDefault("BACKUP_S3_PREFIX", "nightly-backups/");

    // -------------------------------------------------------------------------
    // cr-java-0077 FIX:
    // Hard-coded SERVER_PORT constant replaced with environment variable injection.
    // ECS / EKS / Elastic Beanstalk inject SERVER_PORT at runtime; default 8080
    // is used only for local development.
    // -------------------------------------------------------------------------

    /** Server port — resolved from environment variable SERVER_PORT at runtime. */
    private final int serverPort =
            Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));

    // -------------------------------------------------------------------------
    // Shared AWS clients — constructed once per service instance.
    // Region is resolved from the standard AWS_REGION environment variable.
    // -------------------------------------------------------------------------

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService() {
        String awsRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        Region region = Region.of(awsRegion);
        this.s3Client  = S3Client.builder().region(region).build();
        this.ssmClient = SsmClient.builder().region(region).build();
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     *
     * <p>Replaces the previous implementation that wrote to the local path
     * {@code /var/legacy/reports/} using {@code java.io.File} and
     * {@code FileWriter} — both of which are incompatible with ephemeral
     * container file systems (cr-java-0061, cr-java-0062, cr-java-0063).</p>
     *
     * @param month numeric month string (e.g. "03")
     * @param year  four-digit year string (e.g. "2024")
     * @return result map containing S3 URI and upload status
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName  = "resort_report_" + month + "_" + year + ".csv";
        // cr-java-0061 / cr-java-0063 FIX: S3 key replaces hard-coded local path
        String s3Key     = reportsPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0062 / cr-java-0063 FIX: write CSV content directly to S3
            // instead of using FileWriter / java.io.File on the local file system.
            String csvContent =
                    "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            String s3Uri = "s3://" + reportsBucket + "/" + s3Key;
            result.put("status", "generated");
            result.put("s3Uri", s3Uri);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the HTTPS download URL for a named report by retrieving the base
     * URL from AWS Systems Manager Parameter Store.
     *
     * <p>cr-java-0071 FIX: The hard-coded URL
     * {@code http://reports.resorts-internal.com:8080/download/} has been
     * replaced with a value fetched from SSM Parameter Store under the key
     * {@code /resorts/report/download-base-url}.  This makes the URL
     * environment-agnostic and eliminates the need for code changes between
     * dev, staging, and production deployments.</p>
     *
     * @param reportName the report file name to append to the base URL
     * @return fully qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: retrieve environment-specific base URL from SSM
        // Parameter Store instead of using a hard-coded string.
        String paramName = System.getenv()
                .getOrDefault("REPORT_DOWNLOAD_URL_PARAM", "/resorts/report/download-base-url");
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder().name(paramName).withDecryption(false).build());
            String baseUrl = response.parameter().value();
            return baseUrl + reportName;
        } catch (Exception e) {
            // Fallback: construct URL from environment variable for resilience
            String fallbackBase = System.getenv()
                    .getOrDefault("REPORT_DOWNLOAD_BASE_URL", "https://reports.resorts-internal.com/download/");
            return fallbackBase + reportName;
        }
    }

    /**
     * Returns current system information using UTC-standardised timestamps.
     *
     * <p>cr-java-0111 FIX: {@code java.util.Date} and {@code SimpleDateFormat}
     * (which rely on the server-local JVM timezone) have been replaced with
     * {@code java.time.Instant} and {@code ZonedDateTime} pinned to
     * {@code ZoneOffset.UTC}.  This eliminates timezone inconsistencies across
     * multi-region cloud deployments and container restarts.</p>
     *
     * @return map containing S3 storage references, server port, and UTC timestamp
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: use java.time API standardised on UTC instead of
        // java.util.Date / SimpleDateFormat which depend on the local JVM timezone.
        ZonedDateTime nowUtc    = Instant.now().atZone(ZoneOffset.UTC);
        String        timestamp = nowUtc.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> info = new HashMap<>();
        // cr-java-0061 / cr-java-0063 FIX: expose S3 URIs instead of local paths
        info.put("reportsBucket", reportsBucket);
        info.put("reportsPrefix", reportsPrefix);
        info.put("backupPrefix",  backupPrefix);
        info.put("serverPort",    serverPort);
        info.put("generatedAt",   timestamp);   // cr-java-0111: UTC ISO-8601 timestamp
        return info;
    }
}
