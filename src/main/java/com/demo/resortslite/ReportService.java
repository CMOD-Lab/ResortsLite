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

@Service
public class ReportService {

    // Blocker-1, Blocker-2, Blocker-3: Hard-coded file paths replaced with environment
    // variables / AWS SSM Parameter Store values. No absolute file system paths remain.
    @Value("${cloud.aws.s3.bucket-name:resorts-lite-reports}")
    private String s3BucketName;

    // Blocker-12: Hard-coded port replaced with environment variable injection.
    // Value is resolved at runtime from the environment (ECS/EKS task definition or
    // Elastic Beanstalk environment property), falling back to 8080 for local dev.
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    // Blocker-11: Hard-coded environment URL replaced with AWS SSM Parameter Store lookup.
    // The parameter /resortslite/report/download-base-url is managed in Parameter Store
    // and injected via Spring's @Value with a safe local-dev default.
    @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download}")
    private String reportDownloadBaseUrl;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly report and uploads it to Amazon S3.
     * Blocker-1/2/3/4/5/6/7: All local file-system operations (File, FileWriter,
     * absolute paths) have been replaced with Amazon S3 PutObject calls using
     * AWS SDK for Java v2, ensuring durable, cloud-native storage.
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes();

            // Blocker-4/5/6/7: Upload to S3 instead of writing to local file system
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            String s3Uri = "s3://" + s3BucketName + "/" + objectKey;
            result.put("status", "generated");
            result.put("s3Uri", s3Uri);
            result.put("objectKey", objectKey);
            // Blocker-12: serverPort now sourced from environment variable, not hard-coded
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the base URL retrieved from AWS SSM Parameter Store.
     * Blocker-11: Hard-coded environment URL replaced with Parameter Store lookup.
     */
    public String buildReportDownloadUrl(String reportName) {
        // Resolve the base URL from SSM Parameter Store at runtime
        String baseUrl = getParameterFromSsm("/resortslite/report/download-base-url", reportDownloadBaseUrl);
        return baseUrl + "/" + reportName;
    }

    /**
     * Returns system information using cloud-native configuration values.
     * Blocker-19: java.util.Date replaced with java.time.Instant (UTC) to eliminate
     * timezone and clock-synchronisation issues across distributed cloud instances.
     */
    public Map<String, Object> getSystemInfo() {
        // Blocker-19: Use java.time.Instant with UTC — no server-local timezone dependency
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // Blocker-1/2/3: No absolute file paths — report location is S3 bucket/key
        info.put("s3Bucket", s3BucketName);
        info.put("reportPrefix", "reports/");
        // Blocker-12: Port sourced from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Helper: retrieves a parameter value from AWS SSM Parameter Store.
     * Falls back to the provided default if the parameter cannot be resolved.
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
            return defaultValue;
        }
    }
}
