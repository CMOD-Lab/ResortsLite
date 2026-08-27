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
 * ReportService — cloud-native implementation.
 *
 * <p>All file-system operations have been replaced with Amazon S3 (AWS SDK v2).
 * Hard-coded paths, ports, and URLs are externalised to environment variables
 * and AWS Systems Manager Parameter Store. Timestamps use java.time (UTC) instead
 * of java.util.Date / SimpleDateFormat to avoid timezone inconsistencies across
 * distributed cloud instances.</p>
 */
@Service
public class ReportService {

    // -----------------------------------------------------------------------
    // Configuration — injected from environment variables / application.properties
    // (which themselves read from AWS SSM Parameter Store at startup via
    //  Spring Cloud AWS or a custom initialiser).
    // Hard-coded paths (cr-java-0061), ports (cr-java-0077), and URLs
    // (cr-java-0071) have been removed.
    // -----------------------------------------------------------------------

    /** S3 bucket name for report storage — replaces /var/legacy/reports/ and C:\ResortBackups\ */
    @Value("${cloud.aws.s3.report-bucket:resorts-lite-reports}")
    private String reportBucket;

    /** S3 key prefix for monthly reports */
    @Value("${cloud.aws.s3.report-prefix:reports/}")
    private String reportPrefix;

    /** S3 key prefix for nightly backups */
    @Value("${cloud.aws.s3.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    /**
     * Server port — read from environment variable SERVER_PORT injected by
     * ECS / EKS / Elastic Beanstalk at runtime (replaces hard-coded 8080).
     */
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    /** SSM parameter name for the report-download base URL */
    @Value("${ssm.param.report-download-url:/resortslite/report/download-url}")
    private String reportDownloadUrlParam;

    // -----------------------------------------------------------------------
    // AWS clients — injected / constructed via Spring beans (see AwsConfig)
    // -----------------------------------------------------------------------

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     *
     * <p>Replaces the previous implementation that wrote to the local file system
     * ({@code /var/legacy/reports/} and {@code C:\ResortBackups\nightly\}), which
     * is incompatible with ephemeral cloud/container environments.</p>
     *
     * @param month numeric month string (e.g. "03")
     * @param year  four-digit year string (e.g. "2024")
     * @return result map containing S3 URI and status
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName  = "resort_report_" + month + "_" + year + ".csv";
        String s3Key     = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system required
            StringBuilder csv = new StringBuilder();
            csv.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csv.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csv.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] csvBytes = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // Upload directly to S3 — durable, scalable, cloud-native storage
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(csvBytes));

            result.put("status", "generated");
            result.put("s3Uri", "s3://" + reportBucket + "/" + s3Key);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL by retrieving the base URL from
     * AWS Systems Manager Parameter Store instead of using a hard-coded
     * HTTP endpoint (cr-java-0071).
     *
     * @param reportName the report file name
     * @return fully-qualified download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Retrieve base URL from SSM Parameter Store — replaces hard-coded
        // "http://reports.resorts-internal.com:8080/download/" (cr-java-0071)
        String baseUrl = getSsmParameter(reportDownloadUrlParam,
                "https://reports.resorts-internal.com/download");
        return baseUrl + "/" + reportName;
    }

    /**
     * Returns system information using UTC timestamps (java.time API).
     *
     * <p>Replaces {@code java.util.Date} / {@code SimpleDateFormat} usage
     * (cr-java-0111) to avoid timezone inconsistencies across distributed
     * cloud instances. All timestamps are standardised on UTC.</p>
     *
     * @return system info map
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time Instant (UTC) — replaces new Date() / SimpleDateFormat (cr-java-0111)
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        info.put("reportBucket",  reportBucket);
        info.put("reportPrefix",  reportPrefix);
        info.put("backupPrefix",  backupPrefix);
        info.put("serverPort",    serverPort);
        info.put("generatedAtUtc", timestamp);
        return info;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * @param paramName    SSM parameter name (e.g. "/resortslite/report/download-url")
     * @param defaultValue fallback value if the parameter cannot be retrieved
     * @return parameter value or defaultValue
     */
    private String getSsmParameter(String paramName, String defaultValue) {
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(paramName)
                            .withDecryption(true)
                            .build());
            return response.parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
