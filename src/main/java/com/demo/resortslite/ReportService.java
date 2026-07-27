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

    // Blocker-1,2,3,4,5,6,7 (cr-java-0061, cr-java-0062, cr-java-0063):
    // Hard-coded file paths and local file write operations replaced with Amazon S3.
    // S3 bucket name is injected from environment variable — no hard-coded paths.
    @Value("${cloud.aws.s3.report-bucket:resorts-reports-bucket}")
    private String reportBucket;

    // Blocker-12 (cr-java-0077): Hard-coded port replaced with environment variable injection.
    // Port is now read from the environment at runtime (set via ECS/EKS task definition).
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // Blocker-11 (cr-java-0071): Hard-coded report download URL replaced with value
    // retrieved from AWS Systems Manager Parameter Store at runtime.
    @Value("${app.report.base-url:#{null}}")
    private String reportBaseUrlOverride;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // Blocker-1,2,3 (cr-java-0061): S3 object key replaces hard-coded absolute path.
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Blocker-4,5,6,7 (cr-java-0062, cr-java-0063):
            // Local FileWriter / java.io.File replaced with S3 PutObject for durable storage.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", s3Key);
            // Blocker-12 (cr-java-0077): serverPort now sourced from environment variable.
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Blocker-11 (cr-java-0071): Hard-coded environment URL replaced with value from
     * AWS Systems Manager Parameter Store, enabling environment-agnostic deployments.
     */
    public String buildReportDownloadUrl(String reportName) {
        String baseUrl = resolveReportBaseUrl();
        return baseUrl + "/download/" + reportName;
    }

    /**
     * Resolves the report base URL from AWS SSM Parameter Store.
     * Falls back to the Spring property override if SSM is unavailable.
     */
    private String resolveReportBaseUrl() {
        if (reportBaseUrlOverride != null && !reportBaseUrlOverride.isEmpty()) {
            return reportBaseUrlOverride;
        }
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name("/resortslite/report/base-url")
                    .withDecryption(false)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            // Fall back to a safe default if SSM is unreachable during local development
            return "https://reports.resorts-internal.com";
        }
    }

    public Map<String, Object> getSystemInfo() {
        // Blocker-19 (cr-java-0111): java.util.Date replaced with java.time API (Instant/UTC).
        // Standardised on UTC to avoid timezone inconsistencies across cloud regions.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // Blocker-1,2,3 (cr-java-0061): S3 bucket reference replaces hard-coded file paths.
        info.put("reportBucket", reportBucket);
        // Blocker-12 (cr-java-0077): serverPort sourced from environment variable.
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
