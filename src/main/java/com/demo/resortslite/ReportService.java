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
 * ReportService — cloud-native implementation.
 *
 * <p>All file I/O has been migrated from the local file system to Amazon S3
 * (blocker-1 through blocker-7: cr-java-0061, cr-java-0062, cr-java-0063).
 * Hard-coded environment URLs are resolved via AWS Systems Manager Parameter Store
 * (blocker-11: cr-java-0071).
 * The hard-coded server port is replaced by an environment variable
 * (blocker-12: cr-java-0077).
 * java.util.Date / SimpleDateFormat replaced with java.time API standardised on UTC
 * (blocker-19: cr-java-0111).
 */
@Service
public class ReportService {

    // -------------------------------------------------------------------------
    // blocker-1, blocker-2, blocker-3 (cr-java-0061) — Hard-coded File Paths
    // blocker-4 (cr-java-0062) — Local File System Write Operations
    // blocker-5, blocker-6, blocker-7 (cr-java-0063) — java.io.File Usage
    //
    // Replaced REPORT_BASE_PATH ("/var/legacy/reports/") and BACKUP_PATH
    // ("C:\\ResortBackups\\nightly\\") with an S3 bucket name read from the
    // environment variable REPORTS_S3_BUCKET.  All read/write operations now
    // use the AWS SDK v2 S3Client instead of java.io.File / FileWriter.
    // -------------------------------------------------------------------------
    private final String reportsBucket =
            System.getenv().getOrDefault("REPORTS_S3_BUCKET", "resorts-reports-bucket");

    // -------------------------------------------------------------------------
    // blocker-12 (cr-java-0077) — Hard-coded Ports
    //
    // SERVER_PORT = 8080 replaced by the SERVER_PORT environment variable so
    // that ECS / EKS can inject the correct port at runtime.
    // -------------------------------------------------------------------------
    private final int serverPort =
            Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));

    // -------------------------------------------------------------------------
    // blocker-11 (cr-java-0071) — Hard-coded Environment URLs
    //
    // The hard-coded "http://reports.resorts-internal.com:8080/download/" URL
    // is now resolved from AWS Systems Manager Parameter Store at runtime.
    // The parameter name is configurable via REPORT_DOWNLOAD_URL_PARAM.
    // -------------------------------------------------------------------------
    private final String reportDownloadUrlParamName =
            System.getenv().getOrDefault(
                    "REPORT_DOWNLOAD_URL_PARAM",
                    "/resorts/report/download-base-url");

    private final S3Client s3Client;
    private final SsmClient ssmClient;

    public ReportService() {
        this.s3Client = S3Client.create();
        this.ssmClient = SsmClient.create();
    }

    // Constructor for dependency injection / testing
    public ReportService(S3Client s3Client, SsmClient ssmClient) {
        this.s3Client = s3Client;
        this.ssmClient = ssmClient;
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     *
     * <p>Previously wrote to {@code /var/legacy/reports/} on the local file system
     * (cr-java-0061, cr-java-0062, cr-java-0063). Now uploads directly to S3 so
     * that the data is durable across container restarts and horizontally-scaled
     * instances.
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

            // Upload to S3 (replaces FileWriter to local path)
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucket);
            result.put("s3Key", objectKey);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Returns a pre-signed or base download URL for the given report name.
     *
     * <p>Previously returned a hard-coded {@code http://reports.resorts-internal.com:8080/download/}
     * URL (cr-java-0071). The base URL is now retrieved from AWS Systems Manager
     * Parameter Store, enabling environment-agnostic deployments.
     */
    public String buildReportDownloadUrl(String reportName) {
        // blocker-11 (cr-java-0071): resolve URL from SSM Parameter Store
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(reportDownloadUrlParamName)
                            .withDecryption(false)
                            .build());
            String baseUrl = response.parameter().value();
            return baseUrl + reportName;
        } catch (Exception e) {
            // Fallback: construct S3 console URL so the app remains functional
            return "https://" + reportsBucket + ".s3.amazonaws.com/reports/" + reportName;
        }
    }

    /**
     * Returns system information for diagnostics.
     *
     * <p>blocker-19 (cr-java-0111): {@code java.util.Date} / {@code SimpleDateFormat}
     * replaced with {@code java.time.Instant} standardised on UTC to avoid
     * timezone inconsistencies across cloud regions and container instances.
     */
    public Map<String, Object> getSystemInfo() {
        // blocker-19 (cr-java-0111): use java.time API with explicit UTC zone
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        info.put("reportsBucket", reportsBucket);   // S3 bucket (replaces local path)
        info.put("serverPort", serverPort);          // from env var (replaces hard-coded 8080)
        info.put("generatedAt", timestamp);          // UTC timestamp via java.time
        return info;
    }
}
