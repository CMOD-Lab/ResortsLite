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
 * cloud-native, durable object storage. All file path dependencies have been
 * replaced with S3 operations. Configuration values (bucket name, server port,
 * download URL base) are externalized to AWS Systems Manager Parameter Store
 * and environment variables.
 */
@Service
public class ReportService {

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    /**
     * S3 bucket name injected from environment variable REPORT_S3_BUCKET.
     * Replaces hard-coded absolute file paths (/var/legacy/reports/ and C:\ResortBackups\nightly\).
     */
    @Value("${cloud.aws.s3.report-bucket:${REPORT_S3_BUCKET:resorts-lite-reports}}")
    private String reportBucket;

    /**
     * S3 key prefix for reports, injected from environment variable REPORT_S3_PREFIX.
     */
    @Value("${cloud.aws.s3.report-prefix:${REPORT_S3_PREFIX:reports/}}")
    private String reportPrefix;

    /**
     * Server port injected from environment variable SERVER_PORT.
     * Replaces hard-coded port 8080 (blocker-12 / czr-port-001).
     */
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    /**
     * AWS SSM Parameter Store parameter name for the report download URL base.
     * Replaces hard-coded http://reports.resorts-internal.com:8080/download/ (blocker-11).
     */
    @Value("${ssm.parameter.report-download-url:/resortslite/report/download-url-base}")
    private String reportDownloadUrlParam;

    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly report CSV and uploads it to Amazon S3.
     * Replaces local FileWriter / java.io.File operations (blockers 1-7).
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return result map containing status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String objectKey = reportPrefix + "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // Upload report CSV directly to Amazon S3 — no local file system dependency
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
     * Builds the report download URL by retrieving the base URL from
     * AWS Systems Manager Parameter Store (blocker-11 / cr-java-0071).
     *
     * @param reportName the name of the report file
     * @return fully qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(reportDownloadUrlParam)
                            .withDecryption(false)
                            .build());
            String baseUrl = response.parameter().value();
            return baseUrl + reportName;
        } catch (Exception e) {
            // Fallback: construct URL from environment-injected server port
            return "https://reports.resorts-internal.com:" + serverPort + "/download/" + reportName;
        }
    }

    /**
     * Returns system information using UTC timestamps via java.time API (blocker-19 / cr-java-0111).
     * Replaces java.util.Date / SimpleDateFormat with java.time.Instant standardized on UTC.
     *
     * @return map of system information
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time.Instant for UTC-standardized timestamp — replaces new Date() / SimpleDateFormat
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        info.put("reportBucket", reportBucket);
        info.put("reportPrefix", reportPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }
}
