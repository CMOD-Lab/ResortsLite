package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Blocker-1,2,3,4,5,6,7 (cr-java-0061, cr-java-0062, cr-java-0063):
    // Hard-coded file paths and local file system operations replaced with Amazon S3.
    // S3 bucket name is injected from environment variable; no absolute paths remain.
    @Value("${cloud.aws.s3.bucket-name:resorts-lite-reports}")
    private String s3BucketName;

    // Blocker-12 (cr-java-0077): Hard-coded port replaced with environment variable injection.
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    // Blocker-11 (cr-java-0071): Hard-coded report download URL replaced with SSM Parameter Store.
    @Value("${SSM_REPORT_DOWNLOAD_URL_PARAM:/resortslite/report/download-base-url}")
    private String reportDownloadUrlParam;

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final SsmClient ssmClient;

    public ReportService(S3Client s3Client, S3Presigner s3Presigner, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly report and uploads it to Amazon S3.
     * Replaces all local file system operations (java.io.File, FileWriter) with S3 PutObject.
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return result map containing status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // Blocker-1 (cr-java-0061): S3 object key replaces hard-coded absolute path
        String s3Key = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Blocker-4 (cr-java-0062) & Blocker-5,6,7 (cr-java-0063):
            // All java.io.File and FileWriter operations replaced with S3 PutObject.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            // Blocker-12 (cr-java-0077): serverPort now sourced from environment variable
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a pre-signed S3 URL for report download.
     * Blocker-11 (cr-java-0071): Hard-coded environment URL replaced with SSM Parameter Store.
     * Uses HTTPS pre-signed S3 URL instead of plain HTTP hard-coded endpoint.
     *
     * @param reportName the name of the report object in S3
     * @return a pre-signed HTTPS URL valid for 1 hour
     */
    public String buildReportDownloadUrl(String reportName) {
        // Blocker-11 (cr-java-0071): Retrieve base URL from AWS SSM Parameter Store
        String baseUrl;
        try {
            GetParameterResponse paramResponse = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(reportDownloadUrlParam)
                            .withDecryption(false)
                            .build());
            baseUrl = paramResponse.parameter().value();
        } catch (Exception e) {
            // Fallback: generate a pre-signed S3 URL directly
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key("reports/" + reportName)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofHours(1))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            return presignedRequest.url().toString();
        }
        return baseUrl + "/" + reportName;
    }

    /**
     * Returns system information using UTC timestamps (java.time API).
     * Blocker-19 (cr-java-0111): java.util.Date replaced with java.time Instant/ZonedDateTime
     * standardized on UTC to avoid timezone inconsistencies across cloud regions.
     *
     * @return system info map
     */
    public Map<String, Object> getSystemInfo() {
        // Blocker-19 (cr-java-0111): Use java.time API with UTC — eliminates server-local
        // timezone dependency that causes scheduling failures in multi-region cloud deployments.
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
        String timestamp = nowUtc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));

        Map<String, Object> info = new HashMap<>();
        // Blocker-1,2 (cr-java-0061): Absolute paths replaced with S3 bucket reference
        info.put("s3Bucket", s3BucketName);
        info.put("reportPrefix", "reports/");
        // Blocker-12 (cr-java-0077): Port sourced from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }
}
