package com.demo.resortslite;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // blocker-1, blocker-2, blocker-3: Hard-coded file paths replaced with GCS bucket
    // configured via environment variable / application property (GCP Secret Manager backed).
    // blocker-12: Hard-coded SERVER_PORT replaced with environment-variable-driven property.
    // blocker-11: Hard-coded report download URL replaced with externalized property.

    @Autowired
    private Storage gcsStorage;

    /**
     * GCS bucket name for report storage.
     * Resolved from the environment variable GCS_REPORT_BUCKET_NAME at runtime.
     * Set this value in GCP Cloud Run environment variables or Secret Manager.
     */
    @Value("${gcs.report.bucket.name:${GCS_REPORT_BUCKET_NAME:resorts-reports-bucket}}")
    private String reportBucketName;

    /**
     * Server port externalized to environment variable (blocker-12).
     * Cloud Run / GKE injects PORT automatically; falls back to 8080 for local dev.
     */
    @Value("${server.port:${PORT:8080}}")
    private int serverPort;

    /**
     * Report download base URL externalized to environment variable (blocker-11).
     * Sensitive endpoints should be stored in GCP Secret Manager and referenced via
     * spring.config.import=sm:// in bootstrap.properties.
     */
    @Value("${app.report.download.url:${REPORT_DOWNLOAD_URL:https://reports.resorts-internal.com/download}}")
    private String reportDownloadBaseUrl;

    /**
     * Backup GCS bucket name — replaces the hard-coded Windows path (blocker-2).
     */
    @Value("${gcs.backup.bucket.name:${GCS_BACKUP_BUCKET_NAME:resorts-backup-bucket}}")
    private String backupBucketName;

    /**
     * Generates a monthly report and writes it to Google Cloud Storage (blocker-1 to blocker-7).
     * Replaces all java.io.File and FileWriter operations with GCS SDK calls.
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String objectName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // blocker-4, blocker-5, blocker-6, blocker-7:
            // Replace FileWriter / java.io.File with GCS BlobInfo write.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            BlobId blobId = BlobId.of(reportBucketName, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("text/csv")
                    .build();
            gcsStorage.create(blobInfo, csvContent.getBytes(StandardCharsets.UTF_8));

            String gcsUri = "gs://" + reportBucketName + "/" + objectName;
            result.put("status", "generated");
            result.put("path", gcsUri);
            result.put("serverPort", serverPort); // blocker-12: value from env var

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the externalized base URL (blocker-11).
     * URL is sourced from environment variable / GCP Secret Manager — no hard-coded value.
     */
    public String buildReportDownloadUrl(String reportName) {
        // blocker-11: reportDownloadBaseUrl is injected from env var / Secret Manager
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information using externalized configuration values (blocker-12, blocker-19).
     * Timestamps are standardized to UTC to eliminate server-local timezone dependencies.
     */
    public Map<String, Object> getSystemInfo() {
        // blocker-19: Replace SimpleDateFormat / new Date() (server-local timezone) with
        // UTC-based Instant formatted via DateTimeFormatter — timezone-safe across regions.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // blocker-1, blocker-2: GCS bucket names replace hard-coded local paths
        info.put("reportBucket", reportBucketName);
        info.put("backupBucket", backupBucketName);
        info.put("serverPort", serverPort);   // blocker-12: from env var
        info.put("generatedAt", timestamp);   // blocker-19: UTC timestamp
        return info;
    }
}
