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
 * ReportService handles report generation and storage using cloud-native AWS services.
 *
 * <p>All file operations are delegated to Amazon S3 (replacing local file system paths).
 * Environment-specific URLs and port numbers are externalised to AWS Systems Manager
 * Parameter Store. All timestamps are produced in UTC using the java.time API.</p>
 */
@Service
public class ReportService {

    // S3 bucket name is read from the environment variable REPORT_S3_BUCKET.
    // Set this variable in ECS task definition / Elastic Beanstalk environment configuration.
    private final String reportBucket = System.getenv().getOrDefault("REPORT_S3_BUCKET", "resorts-reports-bucket");

    // S3 key prefix for report objects (replaces hard-coded /var/legacy/reports/ path).
    private static final String REPORT_KEY_PREFIX = "reports/";

    // Server port is read from the SERVER_PORT environment variable injected by ECS/EKS/Beanstalk.
    // Defaults to 8080 only for local development; cloud orchestrators override this at runtime.
    private final int serverPort = Integer.parseInt(
            System.getenv().getOrDefault("SERVER_PORT", "8080"));

    // AWS clients — constructed once per bean instance (thread-safe, reusable).
    private final S3Client s3Client = S3Client.create();
    private final SsmClient ssmClient = SsmClient.create();

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     *
     * @param month the month identifier (e.g. "03")
     * @param year  the four-digit year (e.g. "2024")
     * @return a result map containing the S3 key and upload status
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 object key replaces the former hard-coded absolute file path.
        String s3Key = REPORT_KEY_PREFIX + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes();

            // Upload report to Amazon S3 (replaces FileWriter to local path).
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();
            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL by retrieving the base endpoint from AWS Systems Manager
     * Parameter Store (replaces the former hard-coded HTTP URL).
     *
     * @param reportName the name of the report object in S3
     * @return a fully-qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Retrieve the report service base URL from SSM Parameter Store.
        // The parameter /resorts/report/download-base-url must be set per environment.
        String baseUrl = getParameterFromSsm("/resorts/report/download-base-url",
                "https://reports.resorts-internal.com/download");
        return baseUrl + "/" + reportName;
    }

    /**
     * Returns system information using UTC timestamps (java.time API) and
     * environment-sourced configuration values.
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time Instant for UTC timestamp — replaces java.util.Date / SimpleDateFormat
        // which are timezone-sensitive and unreliable across distributed cloud instances.
        String timestamp = Instant.now()
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> info = new HashMap<>();
        info.put("reportBucket", reportBucket);
        info.put("reportKeyPrefix", REPORT_KEY_PREFIX);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * @param parameterName the SSM parameter path
     * @param defaultValue  fallback value used when the parameter cannot be resolved
     * @return the resolved parameter value or the provided default
     */
    private String getParameterFromSsm(String parameterName, String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            // Fall back to default when running locally or when the parameter is not yet provisioned.
            return defaultValue;
        }
    }
}
