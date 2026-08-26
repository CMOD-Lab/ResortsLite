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

    // GCS bucket name and server port are externalised to environment variables /
    // application properties — no hard-coded file paths or port numbers (fixes
    // cr-java-0061, cr-java-0062, cr-java-0063, cr-java-0077).
    @Value("${gcs.reports.bucket:${GCS_REPORTS_BUCKET:resorts-reports-bucket}}")
    private String reportsBucket;

    @Value("${gcs.backup.bucket:${GCS_BACKUP_BUCKET:resorts-backup-bucket}}")
    private String backupBucket;

    // Hard-coded port replaced with environment-variable-backed property (fixes cr-java-0077).
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // Hard-coded internal URL replaced with externalized property (fixes cr-java-0071).
    @Value("${app.reports.base.url:${REPORTS_BASE_URL:https://reports.resorts-internal.com}}")
    private String reportsBaseUrl;

    @Autowired
    private Storage gcsStorage;

    /**
     * Generates a monthly CSV report and writes it to Google Cloud Storage instead of
     * the local file system, ensuring data durability across container restarts and
     * scaling events (fixes cr-java-0061, cr-java-0062, cr-java-0063).
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String objectName = "monthly/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // Write report to GCS instead of local file system (fixes cr-java-0061,
            // cr-java-0062, cr-java-0063).
            BlobId blobId = BlobId.of(reportsBucket, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("text/csv")
                    .build();
            gcsStorage.create(blobInfo, csvContent.getBytes(StandardCharsets.UTF_8));

            result.put("status", "generated");
            result.put("gcsBucket", reportsBucket);
            result.put("gcsObject", objectName);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the externalized base URL (fixes cr-java-0071).
     * Uses HTTPS to comply with cloud security standards.
     */
    public String buildReportDownloadUrl(String reportName) {
        // Hard-coded HTTP URL replaced with externalized HTTPS URL (fixes cr-java-0071).
        return reportsBaseUrl + "/download/" + reportName;
    }

    /**
     * Returns system information using UTC timestamps to avoid server-local timezone
     * dependencies in distributed cloud environments (fixes cr-java-0111).
     * GCS paths replace local file system paths (fixes cr-java-0061, cr-java-0063).
     */
    public Map<String, Object> getSystemInfo() {
        // Standardised on UTC via java.time.Instant to eliminate server-local timezone
        // dependencies across distributed cloud instances (fixes cr-java-0111).
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now()) + " UTC";

        Map<String, Object> info = new HashMap<>();
        info.put("reportsBucket", reportsBucket);
        info.put("backupBucket", backupBucket);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
