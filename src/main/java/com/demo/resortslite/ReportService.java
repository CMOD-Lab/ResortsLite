package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation backed by Amazon S3.
 *
 * <p>All hard-coded file paths (cr-java-0061, cr-java-0063) and local file-write
 * operations (cr-java-0062) have been replaced with Amazon S3 object storage via
 * AWS SDK for Java v2.  The hard-coded server port (cr-java-0077) is now read from
 * the environment variable {@code SERVER_PORT} (injected by ECS/EKS at runtime).
 * The hard-coded report-download URL (cr-java-0071) is retrieved from AWS Systems
 * Manager Parameter Store.  The legacy {@code java.util.Date} / {@code SimpleDateFormat}
 * usage (cr-java-0111) has been replaced with the {@code java.time} API standardised
 * on UTC.</p>
 */
@Service
public class ReportService {

    // FIX cr-java-0061 / cr-java-0062 / cr-java-0063:
    // S3 bucket name is externalised to an environment variable — no hard-coded paths.
    @Value("${cloud.aws.s3.report-bucket:resorts-reports-bucket}")
    private String reportBucket;

    // FIX cr-java-0077: Server port read from environment variable injected by ECS/EKS.
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    // FIX cr-java-0071: Report-download base URL retrieved from SSM Parameter Store
    // via the application property populated at startup (see application.properties).
    @Value("${app.report.download.url:https://reports.resorts-internal.com/download}")
    private String reportDownloadBaseUrl;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly CSV report and stores it durably in Amazon S3.
     *
     * @param month numeric month (e.g. "03")
     * @param year  four-digit year (e.g. "2024")
     * @return result map containing S3 object key and status
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // FIX cr-java-0061 / cr-java-0062 / cr-java-0063:
        // Object key replaces the former hard-coded absolute file path.
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // FIX cr-java-0062 / cr-java-0063: Write to S3 instead of local FileWriter.
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();
            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", objectKey);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL by retrieving the base URL from AWS Systems Manager
     * Parameter Store, eliminating the hard-coded environment-specific URL.
     *
     * @param reportName the report object key / file name
     * @return fully qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIX cr-java-0071: Retrieve base URL from SSM Parameter Store at runtime.
        String baseUrl;
        try {
            GetParameterRequest paramRequest = GetParameterRequest.builder()
                    .name("/resortslite/report/download-url")
                    .withDecryption(false)
                    .build();
            baseUrl = ssmClient.getParameter(paramRequest).parameter().value();
        } catch (Exception e) {
            // Fall back to the application-property value if SSM is unavailable.
            baseUrl = reportDownloadBaseUrl;
        }
        return baseUrl + "/" + reportName;
    }

    /**
     * Returns current system information using UTC timestamps (java.time API).
     *
     * @return map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // FIX cr-java-0111: Replace java.util.Date / SimpleDateFormat with java.time,
        // standardised on UTC for consistent behaviour across cloud regions.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // FIX cr-java-0061: Replaced hard-coded paths with S3 bucket reference.
        info.put("reportBucket", reportBucket);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
