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

    // Blocker-1/2/3 (cr-java-0061): Hard-coded file paths replaced with environment variable.
    // Blocker-4 (cr-java-0062): Local file write replaced with Amazon S3 upload.
    // Blocker-5/6/7 (cr-java-0063): java.io.File usage replaced with AWS SDK S3Client.
    // Blocker-12 (cr-java-0077): Hard-coded port replaced with AWS SSM Parameter Store / env var.
    // Blocker-11 (cr-java-0071): Hard-coded environment URL replaced with SSM Parameter Store.
    // Blocker-19 (cr-java-0111): java.util.Date/SimpleDateFormat replaced with java.time UTC API.

    /**
     * S3 bucket name for report storage — injected from environment variable.
     * Set REPORT_S3_BUCKET in ECS task definition / Elastic Beanstalk environment.
     */
    @Value("${REPORT_S3_BUCKET:resorts-lite-reports}")
    private String reportBucket;

    /**
     * S3 key prefix for reports — injected from environment variable.
     * Replaces the hard-coded /var/legacy/reports/ and C:\ResortBackups\nightly\ paths.
     */
    @Value("${REPORT_S3_PREFIX:reports/}")
    private String reportPrefix;

    /**
     * Server port — injected from environment variable (set by ECS/EKS at runtime).
     * Replaces the hard-coded SERVER_PORT = 8080 constant.
     */
    @Value("${SERVER_PORT:${server.port:8080}}")
    private int serverPort;

    /**
     * SSM Parameter Store parameter name for the report download base URL.
     * Replaces the hard-coded http://reports.resorts-internal.com:8080/download/ URL.
     */
    @Value("${SSM_REPORT_DOWNLOAD_URL_PARAM:/resortslite/report/download-base-url}")
    private String reportDownloadUrlParam;

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly report and uploads it to Amazon S3.
     * Replaces local FileWriter / java.io.File operations with S3 PutObject.
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes();

            // Upload report to Amazon S3 (replaces FileWriter to local path)
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
     * Builds the report download URL by retrieving the base URL from AWS SSM Parameter Store.
     * Replaces the hard-coded http://reports.resorts-internal.com:8080/download/ URL.
     */
    public String buildReportDownloadUrl(String reportName) {
        try {
            GetParameterResponse paramResponse = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(reportDownloadUrlParam)
                            .withDecryption(false)
                            .build());
            String baseUrl = paramResponse.parameter().value();
            return baseUrl + reportName;
        } catch (Exception e) {
            // Fallback to environment variable if SSM is unavailable
            String baseUrl = System.getenv("REPORT_DOWNLOAD_BASE_URL");
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = "https://reports.resorts-internal.com/download/";
            }
            return baseUrl + reportName;
        }
    }

    /**
     * Returns system information using cloud-native configuration values.
     * Replaces hard-coded paths and ports with environment-injected values.
     * Uses java.time Instant (UTC) instead of java.util.Date for cloud-safe timestamps.
     */
    public Map<String, Object> getSystemInfo() {
        // Blocker-19 (cr-java-0111): Use java.time UTC API instead of java.util.Date
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        info.put("reportBucket", reportBucket);
        info.put("reportPrefix", reportPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
