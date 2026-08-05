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

@Service
public class ReportService {

    // Replaced hard-coded file paths (cr-java-0061) with environment variables pointing to S3.
    // S3 bucket name is read from the environment variable REPORT_S3_BUCKET (cr-java-0061).
    private final String reportS3Bucket;

    // Replaced hard-coded server port (cr-java-0077) with AWS SSM Parameter Store lookup
    // injected via environment variable SERVER_PORT at runtime.
    private final int serverPort;

    // S3 client (AWS SDK v2) replaces all java.io.File / FileWriter operations (cr-java-0063).
    private final S3Client s3Client;

    // SSM client for retrieving environment-specific URLs (cr-java-0071).
    private final SsmClient ssmClient;

    public ReportService() {
        this.s3Client = S3Client.create();
        this.ssmClient = SsmClient.create();

        // Read S3 bucket from environment variable; fall back to a safe default.
        String bucket = System.getenv("REPORT_S3_BUCKET");
        this.reportS3Bucket = (bucket != null && !bucket.isEmpty()) ? bucket : "resorts-reports-bucket";

        // Read server port from environment variable (cr-java-0077); default to 8080.
        String portEnv = System.getenv("SERVER_PORT");
        this.serverPort = (portEnv != null && !portEnv.isEmpty()) ? Integer.parseInt(portEnv) : 8080;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 key replaces the hard-coded absolute path /var/legacy/reports/ (cr-java-0061).
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local File or FileWriter (cr-java-0062, cr-java-0063).
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // Upload directly to Amazon S3 for durable, cloud-native storage (cr-java-0062).
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportS3Bucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();
            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", reportS3Bucket);
            result.put("s3Key", s3Key);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        // Retrieve the report download base URL from AWS SSM Parameter Store (cr-java-0071).
        // The parameter /resorts/report/download-url must be set per environment.
        String baseUrl = getParameterFromSsm("/resorts/report/download-url",
                "https://reports.resorts-internal.com/download");
        return baseUrl + "/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        // Replaced java.util.Date / SimpleDateFormat with java.time API standardised on UTC
        // to eliminate timezone and clock-synchronisation issues (cr-java-0111).
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // Expose S3 references instead of local file paths (cr-java-0061).
        info.put("reportS3Bucket", reportS3Bucket);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Helper: retrieve a parameter value from AWS SSM Parameter Store.
     * Falls back to {@code defaultValue} if the parameter is not found or SSM is unavailable.
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
