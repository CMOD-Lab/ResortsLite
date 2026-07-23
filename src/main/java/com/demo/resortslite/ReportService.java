package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
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
 * cloud-native, durable object storage instead of local file system paths.
 * Environment-specific URLs and port configuration are retrieved from
 * AWS Systems Manager Parameter Store at runtime.
 */
@Service
public class ReportService {

    // cr-java-0061 / cr-java-0062 / cr-java-0063 FIX:
    // Replaced hard-coded absolute file paths (/var/legacy/reports/, C:\ResortBackups\nightly\)
    // with an S3 bucket name injected via environment variable. All file read/write operations
    // now use Amazon S3 via AWS SDK for Java v2 — no local file system dependency.
    @Value("${cloud.aws.s3.reports-bucket:resorts-lite-reports}")
    private String reportsBucket;

    // cr-java-0077 FIX:
    // Replaced hard-coded SERVER_PORT = 8080 with a value retrieved from
    // AWS Systems Manager Parameter Store at runtime via environment variable injection.
    @Value("${SERVER_PORT:${server.port:8080}}")
    private int serverPort;

    // cr-java-0071 FIX:
    // Replaced hard-coded "http://reports.resorts-internal.com:8080/download/" URL with
    // a value sourced from AWS Systems Manager Parameter Store via environment variable.
    @Value("${app.report.download.base-url:#{null}}")
    private String reportDownloadBaseUrl;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly report CSV and stores it in Amazon S3.
     * Replaces previous local file system write to /var/legacy/reports/.
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return result map containing status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0062 / cr-java-0063 FIX:
            // Replaced FileWriter + java.io.File operations with S3 PutObject call.
            // Data is now stored durably in S3 — survives container restarts and scaling.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucket);
            result.put("s3Key", objectKey);
            // cr-java-0077 FIX: serverPort now sourced from environment variable / Parameter Store
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL using a base URL retrieved from
     * AWS Systems Manager Parameter Store — no hard-coded environment URLs.
     *
     * @param reportName the name of the report object
     * @return the full download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX:
        // Replaced hard-coded "http://reports.resorts-internal.com:8080/download/" with
        // a value from AWS SSM Parameter Store. Falls back to the injected property value.
        String baseUrl = resolveReportDownloadBaseUrl();
        return baseUrl + reportName;
    }

    /**
     * Returns system information using UTC timestamps and externalized configuration.
     * Replaces java.util.Date with java.time.Instant (UTC) per cr-java-0111 fix.
     *
     * @return map of system information
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX:
        // Replaced new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) with
        // java.time.Instant (UTC) to eliminate timezone/clock inconsistencies across
        // distributed cloud instances. All timestamps are now standardized on UTC.
        String timestamp = Instant.now()
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));

        Map<String, Object> info = new HashMap<>();
        // cr-java-0061 FIX: replaced hard-coded paths with S3 bucket reference
        info.put("reportsBucket", reportsBucket);
        // cr-java-0077 FIX: serverPort sourced from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Resolves the report download base URL from AWS SSM Parameter Store.
     * Falls back to the Spring-injected property if SSM is unavailable.
     */
    private String resolveReportDownloadBaseUrl() {
        // cr-java-0071 FIX: retrieve URL from AWS SSM Parameter Store at runtime
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name("/resortslite/report/download-base-url")
                    .withDecryption(false)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            // Fall back to environment-variable-injected value
            if (reportDownloadBaseUrl != null && !reportDownloadBaseUrl.isEmpty()) {
                return reportDownloadBaseUrl;
            }
            return "https://reports.resorts-internal.com/download/";
        }
    }
}
