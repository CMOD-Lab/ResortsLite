package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
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

/**
 * ReportService — cloud-native implementation.
 *
 * FIX cr-java-0061 (blockers 1-3): All hard-coded absolute file paths removed.
 *   REPORT_BASE_PATH (/var/legacy/reports/) and BACKUP_PATH (C:\ResortBackups\)
 *   replaced with Amazon S3 bucket + key prefix, configured via environment variables.
 *
 * FIX cr-java-0062 (blocker 4): Local FileWriter write operations replaced with
 *   Amazon S3 PutObject calls for durable, scalable object storage.
 *
 * FIX cr-java-0063 (blockers 5-7): java.io.File usage eliminated entirely.
 *   Directory creation (reportDir.mkdirs()) and file construction replaced with
 *   S3 key composition and AWS SDK v2 S3Client operations.
 *
 * FIX cr-java-0071 (blocker 11): Hard-coded report download URL replaced with
 *   a pre-signed S3 URL generated at runtime, or a value from AWS SSM Parameter Store.
 *
 * FIX cr-java-0077 (blocker 12): Hard-coded SERVER_PORT constant removed.
 *   Port is now read from the SERVER_PORT environment variable (injected by ECS/EKS)
 *   with a safe default of 8080.
 *
 * FIX cr-java-0111 (blocker 19): java.util.Date and SimpleDateFormat replaced with
 *   java.time.Instant / ZonedDateTime standardized on UTC, eliminating timezone
 *   inconsistencies across distributed cloud instances.
 */
@Service
public class ReportService {

    // FIX cr-java-0061 / cr-java-0062 / cr-java-0063: S3 bucket and key prefix
    // replace all hard-coded local file paths. Values are injected via environment
    // variables so the application is portable across dev / staging / production.
    @Value("${aws.s3.reports-bucket:${REPORTS_S3_BUCKET:resortslite-reports}}")
    private String reportsBucket;

    @Value("${aws.s3.reports-prefix:${REPORTS_S3_PREFIX:monthly-reports/}}")
    private String reportsPrefix;

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    // FIX cr-java-0071 (blocker 11): Report download base URL externalized to
    // AWS SSM Parameter Store. Parameter name is injected via environment variable.
    @Value("${aws.ssm.report-download-url-param:${REPORT_DOWNLOAD_URL_PARAM:/resortslite/reports/download-base-url}}")
    private String reportDownloadUrlParamName;

    // FIX cr-java-0077 (blocker 12): Hard-coded SERVER_PORT constant replaced with
    // environment variable injection. ECS / EKS injects SERVER_PORT dynamically.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     *
     * FIX cr-java-0061 / cr-java-0062 / cr-java-0063: No local file system access.
     * Report content is written directly to S3 via PutObjectRequest with RequestBody.
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // FIX cr-java-0061: S3 object key replaces hard-coded absolute file path.
        String s3Key = reportsPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try (S3Client s3Client = S3Client.builder()
                .region(Region.of(awsRegion))
                .build()) {

            // FIX cr-java-0062 / cr-java-0063: FileWriter and java.io.File replaced
            // with in-memory content uploaded directly to Amazon S3.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            // FIX cr-java-0061: S3 URI returned instead of local file path.
            result.put("s3Uri", "s3://" + reportsBucket + "/" + s3Key);
            // FIX cr-java-0077: serverPort resolved from environment variable, not constant.
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a pre-signed S3 download URL for the given report object key.
     *
     * FIX cr-java-0071 (blocker 11): Hard-coded "http://reports.resorts-internal.com:8080/download/"
     * URL replaced with a time-limited pre-signed S3 URL generated via AWS SDK v2 S3Presigner,
     * or a base URL retrieved from AWS SSM Parameter Store.
     */
    public String buildReportDownloadUrl(String reportName) {
        // Attempt to generate a pre-signed S3 URL (preferred cloud-native approach).
        try (S3Presigner presigner = S3Presigner.builder()
                .region(Region.of(awsRegion))
                .build()) {

            String s3Key = reportsPrefix + reportName;

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(60))
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(reportsBucket)
                            .key(s3Key)
                            .build())
                    .build();

            PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(presignRequest);
            return presignedRequest.url().toString();

        } catch (Exception e) {
            // Fallback: retrieve base URL from SSM Parameter Store.
            return getReportBaseUrlFromSsm() + reportName;
        }
    }

    /**
     * Returns system information using cloud-native configuration values.
     *
     * FIX cr-java-0061: Local file paths replaced with S3 bucket/prefix references.
     * FIX cr-java-0077: serverPort resolved from environment variable.
     * FIX cr-java-0111: java.util.Date / SimpleDateFormat replaced with
     *   java.time.ZonedDateTime in UTC for consistent timestamps across cloud regions.
     */
    public Map<String, Object> getSystemInfo() {
        // FIX cr-java-0111: UTC timestamp via java.time API — no server-local timezone dependency.
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));

        Map<String, Object> info = new HashMap<>();
        // FIX cr-java-0061: S3 references replace hard-coded local paths.
        info.put("reportsBucket", reportsBucket);
        info.put("reportsPrefix", reportsPrefix);
        // FIX cr-java-0077: serverPort from environment variable, not hard-coded constant.
        info.put("serverPort", serverPort);
        // FIX cr-java-0111: UTC-standardized timestamp.
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Retrieves the report download base URL from AWS SSM Parameter Store.
     * Used as a fallback when S3 pre-signing is unavailable.
     */
    private String getReportBaseUrlFromSsm() {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(reportDownloadUrlParamName)
                            .withDecryption(false)
                            .build());
            return response.parameter().value();

        } catch (Exception e) {
            // Final fallback to environment variable
            return System.getenv().getOrDefault("REPORT_DOWNLOAD_BASE_URL",
                    "https://reports.resorts-internal.com/download/");
        }
    }
}
