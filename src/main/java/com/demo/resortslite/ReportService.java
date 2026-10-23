package com.demo.resortslite;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native implementation.
 *
 * <p>All file I/O has been migrated to Amazon S3 (AWS SDK for Java v2).
 * Hard-coded file paths, Windows-style backup paths, and local FileWriter
 * calls have been removed. Port configuration is externalised to AWS Systems
 * Manager Parameter Store. All timestamps are produced in UTC using the
 * java.time API (Instant / ZonedDateTime) instead of the legacy
 * java.util.Date / SimpleDateFormat combination.</p>
 */
@Service
public class ReportService {

    // S3 bucket name is read from an environment variable so it can differ
    // per deployment environment without any code change (12-factor principle).
    private static final String S3_BUCKET =
            System.getenv().getOrDefault("REPORTS_S3_BUCKET", "resorts-lite-reports");

    // AWS region is also externalised via environment variable.
    private static final String AWS_REGION =
            System.getenv().getOrDefault("AWS_REGION", "us-east-1");

    // SSM parameter name that holds the server port — injected at runtime by
    // ECS / EKS / Elastic Beanstalk so no port is hard-coded in source.
    private static final String SSM_PORT_PARAM =
            System.getenv().getOrDefault("SSM_SERVER_PORT_PARAM", "/resortslite/server/port");

    // SSM parameter name that holds the report-download base URL.
    private static final String SSM_REPORT_URL_PARAM =
            System.getenv().getOrDefault("SSM_REPORT_URL_PARAM", "/resortslite/reports/download-url");

    // Lazy-initialised AWS clients — created once per JVM instance.
    private S3Client s3Client;
    private SsmClient ssmClient;

    private S3Client getS3Client() {
        if (s3Client == null) {
            s3Client = S3Client.builder()
                    .region(Region.of(AWS_REGION))
                    .build();
        }
        return s3Client;
    }

    private SsmClient getSsmClient() {
        if (ssmClient == null) {
            ssmClient = SsmClient.builder()
                    .region(Region.of(AWS_REGION))
                    .build();
        }
        return ssmClient;
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     *
     * <p>Replaces the previous implementation that wrote files to the local
     * path {@code /var/legacy/reports/} and {@code C:\ResortBackups\nightly\},
     * both of which are unavailable in cloud / container environments.</p>
     *
     * @param month numeric or named month string (e.g. "03" or "March")
     * @param year  four-digit year string (e.g. "2024")
     * @return result map containing S3 key, bucket, and server port
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String s3Key = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system required.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // Upload directly to S3 — durable, available, and scalable storage.
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(S3_BUCKET)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            getS3Client().putObject(putRequest, RequestBody.fromBytes(contentBytes));

            // Retrieve server port from SSM Parameter Store at runtime.
            int serverPort = getServerPortFromSsm();

            result.put("status", "generated");
            result.put("s3Bucket", S3_BUCKET);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL by reading the base URL from AWS Systems
     * Manager Parameter Store instead of using a hard-coded HTTP endpoint.
     *
     * <p>Replaces the previous hard-coded value
     * {@code http://reports.resorts-internal.com:8080/download/} which was
     * environment-specific and used plain HTTP.</p>
     *
     * @param reportName the name of the report file to download
     * @return fully-qualified download URL retrieved from Parameter Store
     */
    public String buildReportDownloadUrl(String reportName) {
        try {
            GetParameterResponse response = getSsmClient().getParameter(
                    GetParameterRequest.builder()
                            .name(SSM_REPORT_URL_PARAM)
                            .withDecryption(false)
                            .build());
            String baseUrl = response.parameter().value();
            return baseUrl + reportName;
        } catch (Exception e) {
            // Fall back to environment variable if SSM is unavailable.
            String fallback = System.getenv().getOrDefault(
                    "REPORT_DOWNLOAD_BASE_URL", "https://reports.resorts-internal.com/download/");
            return fallback + reportName;
        }
    }

    /**
     * Returns system information using UTC timestamps (java.time API) and
     * S3-based storage references instead of local file system paths.
     *
     * <p>Replaces {@code java.util.Date} / {@code SimpleDateFormat} with
     * {@code java.time.Instant} and {@code ZonedDateTime} standardised on UTC
     * to avoid timezone inconsistencies across cloud regions and containers.</p>
     *
     * @return map containing S3 bucket, server port, and UTC timestamp
     */
    public Map<String, Object> getSystemInfo() {
        // Use java.time API with explicit UTC zone — no server-local timezone dependency.
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
        String timestamp = nowUtc.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        int serverPort = getServerPortFromSsm();

        Map<String, Object> info = new HashMap<>();
        info.put("reportsBucket", S3_BUCKET);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Retrieves the server port from AWS Systems Manager Parameter Store.
     * Falls back to the {@code SERVER_PORT} environment variable, and finally
     * to 8080 if neither source is available.
     *
     * @return server port integer
     */
    private int getServerPortFromSsm() {
        try {
            GetParameterResponse response = getSsmClient().getParameter(
                    GetParameterRequest.builder()
                            .name(SSM_PORT_PARAM)
                            .withDecryption(false)
                            .build());
            return Integer.parseInt(response.parameter().value());
        } catch (Exception e) {
            String envPort = System.getenv("SERVER_PORT");
            if (envPort != null && !envPort.isEmpty()) {
                try {
                    return Integer.parseInt(envPort);
                } catch (NumberFormatException nfe) {
                    // fall through to default
                }
            }
            return 8080;
        }
    }
}
