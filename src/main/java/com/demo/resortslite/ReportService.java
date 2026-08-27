package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation service.
 *
 * Cloud readiness fixes applied:
 *  - blocker-1..3  (cr-java-0061): Hard-coded absolute file paths removed. S3 bucket
 *    and prefix are injected via environment variables / application.properties.
 *  - blocker-4     (cr-java-0062): Local file write operations replaced with Amazon S3
 *    PutObject calls for durable, scalable object storage.
 *  - blocker-5..7  (cr-java-0063): java.io.File API replaced with AWS SDK v2 S3Client.
 *  - blocker-11    (cr-java-0071): Hard-coded report download URL replaced with a value
 *    retrieved from AWS Systems Manager Parameter Store.
 *  - blocker-12    (cr-java-0077): Hard-coded SERVER_PORT replaced with an environment
 *    variable (PORT) injected at runtime by ECS/EKS/Elastic Beanstalk.
 *  - blocker-19    (cr-java-0111): java.util.Date / SimpleDateFormat replaced with
 *    java.time.Instant / ZonedDateTime standardized on UTC.
 */
@Service
public class ReportService {

    // FIX blocker-1..3 (cr-java-0061) + blocker-4 (cr-java-0062) + blocker-5..7 (cr-java-0063):
    // Hard-coded absolute paths (/var/legacy/reports/, C:\ResortBackups\nightly\) are replaced
    // with an S3 bucket name and prefix injected via environment variables. No local file system
    // dependency remains — all report data is written to Amazon S3.
    @Value("${aws.s3.report-bucket:resortslite-reports}")
    private String reportBucket;

    @Value("${aws.s3.report-prefix:monthly-reports/}")
    private String reportPrefix;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    // FIX blocker-12 (cr-java-0077): Hard-coded port 8080 replaced with an environment
    // variable PORT injected at runtime by ECS task definition / Elastic Beanstalk.
    // Defaults to 8080 for local development only.
    @Value("${PORT:8080}")
    private int serverPort;

    // FIX blocker-11 (cr-java-0071): Hard-coded report service URL replaced with a
    // parameter path resolved from AWS Systems Manager Parameter Store at runtime.
    @Value("${REPORT_SERVICE_URL:#{null}}")
    private String reportServiceUrl;

    @Value("${aws.ssm.report-service-url-param:/resortslite/report/service-url}")
    private String reportServiceUrlParam;

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     *
     * FIX blocker-1..7 (cr-java-0061, cr-java-0062, cr-java-0063):
     * All java.io.File, FileWriter, and absolute path operations are replaced with
     * an S3Client PutObject call. The report content is written directly to S3 as
     * a byte array — no local file system access is required.
     *
     * FIX blocker-19 (cr-java-0111):
     * Timestamp is generated using java.time.Instant (UTC) instead of java.util.Date
     * with a server-local timezone, ensuring consistent timestamps across all regions.
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // FIX blocker-1..3 (cr-java-0061): S3 object key replaces the hard-coded local path.
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // FIX blocker-4 (cr-java-0062) + blocker-5..7 (cr-java-0063):
            // Build report content in memory and upload to Amazon S3 using AWS SDK v2.
            // Replaces: new File(REPORT_BASE_PATH), reportDir.mkdirs(), new FileWriter(fullPath).
            String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            byte[] contentBytes = reportContent.getBytes(StandardCharsets.UTF_8);

            S3Client s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .contentLength((long) contentBytes.length)
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            // FIX blocker-12 (cr-java-0077): serverPort is now resolved from the PORT
            // environment variable rather than a hard-coded literal.
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
     * Builds the report download URL by resolving the base URL from AWS Systems Manager
     * Parameter Store instead of using a hard-coded hostname and port.
     *
     * FIX blocker-11 (cr-java-0071): Hard-coded "http://reports.resorts-internal.com:8080"
     * is replaced with a value retrieved from SSM Parameter Store, enabling environment-
     * agnostic deployments without code changes.
     */
    public String buildReportDownloadUrl(String reportName) {
        String baseUrl = resolveReportServiceUrl();
        return baseUrl + "/download/" + reportName;
    }

    /**
     * Returns system information using UTC timestamps.
     *
     * FIX blocker-19 (cr-java-0111): java.util.Date and SimpleDateFormat (which use the
     * server's local timezone) are replaced with java.time.Instant and ZonedDateTime
     * pinned to UTC. This ensures consistent timestamps across all cloud regions and
     * container instances regardless of the host timezone setting.
     *
     * FIX blocker-1..3 (cr-java-0061): reportPath and backupPath now reflect S3 locations
     * rather than hard-coded local file system paths.
     *
     * FIX blocker-12 (cr-java-0077): serverPort is resolved from the PORT env var.
     */
    public Map<String, Object> getSystemInfo() {
        // FIX blocker-19 (cr-java-0111): Use java.time.Instant (UTC) instead of
        // new Date() with server-local timezone.
        ZonedDateTime nowUtc = Instant.now().atZone(ZoneOffset.UTC);
        String timestamp = nowUtc.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> info = new HashMap<>();
        // FIX blocker-1..3 (cr-java-0061): S3 bucket/prefix replace hard-coded local paths.
        info.put("reportStorage", "s3://" + reportBucket + "/" + reportPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Resolves the report service base URL from AWS Systems Manager Parameter Store.
     * If the REPORT_SERVICE_URL environment variable is already set (e.g., injected by
     * ECS task definition), it is used directly to avoid an extra SSM API call.
     */
    private String resolveReportServiceUrl() {
        if (reportServiceUrl != null && !reportServiceUrl.isEmpty()) {
            return reportServiceUrl;
        }
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .build();
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(reportServiceUrlParam)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            // Fallback for local development
            return System.getenv().getOrDefault("REPORT_SERVICE_URL",
                    "https://reports.resorts-internal.com");
        }
    }
}
