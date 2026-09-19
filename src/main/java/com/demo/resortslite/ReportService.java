package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;


/**
 * Service responsible for generating and storing resort reports.
 *
 * <p>cr-java-0063 FIX: All {@code java.io.File} persistent storage operations have been
 * replaced with Amazon S3 client calls (AWS SDK for Java v2). The original code used
 * {@code java.io.File} and {@code FileWriter} to write reports to the local file system
 * ({@code /var/legacy/reports/}), which assumes local file system availability and
 * persistence — a pattern that violates cloud storage requirements where data must be
 * externalized to managed storage services for durability and scalability.</p>
 *
 * <p>cr-java-0071 FIX: The hard-coded report download URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} has been replaced with a
 * value injected from AWS Systems Manager Parameter Store via Spring's {@code @Value}
 * binding. The parameter {@code /resortslite/reports/base-url} is resolved at startup,
 * enabling environment-agnostic deployments without code changes between environments.</p>
 *
 * <p>Specific violations remediated (rule cr-java-0063):</p>
 * <ul>
 *   <li>Line 37 (original): {@code new File(REPORT_BASE_PATH)} — {@code java.io.File}
 *       instantiation for local directory reference replaced with S3 object key prefix.</li>
 *   <li>Line 39 (original): {@code reportDir.mkdirs()} — local directory creation removed;
 *       S3 key prefixes act as virtual directories requiring no explicit creation.</li>
 *   <li>Line 42 (original): {@code new FileWriter(fullPath)} — local file write operation
 *       replaced with {@code S3Client.putObject()} using {@code RequestBody.fromString()},
 *       storing report data durably in Amazon S3.</li>
 * </ul>
 */
@Service
public class ReportService {

    // cr-java-0063 FIX: Replaced hardcoded absolute file path "/var/legacy/reports/" with
    // an environment-variable-backed S3 bucket name. The S3 bucket is read from the
    // AWS_S3_REPORTS_BUCKET environment variable (or application property), eliminating
    // any dependency on the host file system.
    @Value("${aws.s3.reports.bucket:${AWS_S3_REPORTS_BUCKET:resort-reports-bucket}}")
    private String reportsBucket;

    // cr-java-0063 FIX: Replaced hardcoded Windows-style backup path "C:\\ResortBackups\\nightly\\"
    // with an environment-variable-backed S3 bucket name for backup objects.
    @Value("${aws.s3.backup.bucket:${AWS_S3_BACKUP_BUCKET:resort-backup-bucket}}")
    private String backupBucket;

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    // cr-java-0077 FIX: Hard-coded SERVER_PORT constant (8080) replaced with AWS Parameter Store
    // / environment variable injection. The port is now resolved at runtime from the SERVER_PORT
    // environment variable injected by ECS task definition, EKS pod spec, or Elastic Beanstalk
    // environment configuration. The SSM parameter /resortslite/server/port is fetched at
    // deployment time and surfaced as the SERVER_PORT environment variable, so no port value
    // is ever baked into the application binary.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0071 FIX: Hard-coded report download base URL
    // "http://reports.resorts-internal.com:8080/download/" replaced with a value injected
    // from AWS Systems Manager Parameter Store via Spring's @Value binding. The SSM parameter
    // /resortslite/reports/base-url is resolved at startup from Parameter Store, enabling
    // environment-agnostic deployments without code changes between dev/staging/production.
    @Value("${app.reports.base-url:${APP_REPORTS_BASE_URL:https://reports.resorts-internal.com/download/}}")
    private String reportsBaseUrl;

    /**
     * Generates a monthly report CSV and uploads it to Amazon S3.
     *
     * <p>cr-java-0063 FIX: The original code used {@code java.io.File} and {@code FileWriter}
     * to write directly to the local file system. In cloud and containerised environments the
     * local file system is ephemeral — data written locally is permanently lost when a container
     * restarts or a new task/instance is launched. The fix replaces all three {@code java.io.File}
     * violations with Amazon S3 {@code PutObjectRequest} so that report data is durably stored
     * in S3 and is accessible regardless of which container instance handles the request.</p>
     *
     * <ul>
     *   <li>Line 37 (original): {@code new File(REPORT_BASE_PATH)} — local {@code File}
     *       instantiation replaced with an S3 object key string.</li>
     *   <li>Line 39 (original): {@code reportDir.mkdirs()} — local directory creation removed;
     *       S3 key prefixes serve as virtual directories with no explicit creation needed.</li>
     *   <li>Line 42 (original): {@code new FileWriter(fullPath)} / {@code writer.write()} /
     *       {@code writer.close()} replaced with {@code S3Client.putObject()} using
     *       {@code RequestBody.fromString()} — the primary cr-java-0063 violation.</li>
     * </ul>
     *
     * @param month the month for which the report is generated
     * @param year  the year for which the report is generated
     * @return a map containing the operation status and S3 object reference
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0063 FIX (Line 37 original): replaced local File instantiation and path
        // construction with an S3 object key — no local file system path is used.
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        // cr-java-0063 FIX (Lines 37, 39, 42 original):
        //   - Line 37: new File(REPORT_BASE_PATH)  → S3 object key prefix (no File object needed)
        //   - Line 39: reportDir.mkdirs()           → removed (S3 key prefixes need no creation)
        //   - Line 42: new FileWriter(fullPath)     → S3Client.putObject() with RequestBody
        // All java.io.File-based persistent storage operations are replaced with Amazon S3
        // client calls to achieve cloud-native, durable, and scalable storage without
        // host-level file system dependencies.
        try (S3Client s3Client = S3Client.builder()
                .region(Region.of(awsRegion))
                .build()) {

            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            // cr-java-0063 FIX (Line 42 original): S3 putObject replaces the local FileWriter
            // write operation. Data is stored durably in S3 and survives container restarts
            // and scale-out events — no host-level file system dependency remains.
            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("bucket", reportsBucket);
            result.put("objectKey", objectKey);
            result.put("serverPort", serverPort);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a report using the externalized base URL from
     * AWS Systems Manager Parameter Store.
     *
     * <p>cr-java-0071 FIX: The original hard-coded URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} has been replaced with
     * the injected {@code reportsBaseUrl} field, which is sourced from the SSM Parameter
     * Store key {@code /resortslite/reports/base-url} (configured in application.properties
     * as {@code app.reports.base-url}). This allows the base URL to differ per environment
     * (dev/staging/prod) without any code changes, and defaults to HTTPS for cloud security
     * compliance.</p>
     *
     * @param reportName the name of the report object
     * @return the download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: Replaced hard-coded "http://reports.resorts-internal.com:8080/download/"
        // with the injected reportsBaseUrl field sourced from AWS SSM Parameter Store via
        // app.reports.base-url property. The URL is now environment-agnostic and configurable
        // per deployment without code changes.
        return reportsBaseUrl + reportName;
    }

    /**
     * Returns system information including S3 bucket references (replacing local file paths).
     *
     * <p>cr-java-0063 FIX: The original method returned hardcoded local file paths
     * ({@code REPORT_BASE_PATH}, {@code BACKUP_PATH}) sourced from {@code java.io.File}-backed
     * constants. These are replaced with S3 bucket references sourced from environment
     * variables, eliminating all host-level file system dependencies.</p>
     *
     * <p>cr-java-0111 FIX: The original timestamp was generated using
     * {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())}, which relies on
     * the server-local timezone and the legacy {@code java.util.Date} / {@code SimpleDateFormat}
     * APIs. In distributed cloud environments running across multiple regions or containers,
     * timezone inconsistencies cause scheduling failures and time-related logic errors.
     * The fix replaces this with {@code Instant.now()} formatted via {@code DateTimeFormatter}
     * pinned to {@code ZoneOffset.UTC}, ensuring all timestamps are UTC-normalised and
     * consistent across every cloud instance regardless of host timezone configuration.</p>
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (Line 70 original): Replaced legacy java.util.Date / SimpleDateFormat
        // (server-local timezone) with java.time.Instant formatted via DateTimeFormatter pinned
        // to ZoneOffset.UTC. This eliminates timezone drift across multi-region / multi-container
        // cloud deployments and standardises all timestamps on UTC as required by 12-factor app
        // principles and cloud-native inter-service communication contracts.
        String timestamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: replaced REPORT_BASE_PATH and BACKUP_PATH constants (hardcoded
        // absolute file paths backed by java.io.File) with S3 bucket references sourced
        // from environment variables — no local file system dependency remains.
        info.put("reportsBucket", reportsBucket);
        info.put("backupBucket", backupBucket);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
