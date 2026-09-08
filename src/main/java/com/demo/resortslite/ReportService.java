package com.demo.resortslite;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation backed by Google Cloud Storage.
 *
 * <p>All local file-system operations (blockers 1-7: cr-java-0061, cr-java-0062,
 * cr-java-0063) have been replaced with GCS SDK calls.  The hard-coded server port
 * (blocker-12: cr-java-0077) is now read from the {@code PORT} environment variable.
 * The hard-coded report download URL (blocker-11: cr-java-0071) is externalised to
 * {@code app.report.download.base-url}.  The local {@code java.util.Date} / timezone
 * dependency (blocker-19: cr-java-0111) is replaced with a UTC-based
 * {@link java.time.Instant} timestamp.
 */
@Service
public class ReportService {

    // -----------------------------------------------------------------------
    // Blocker-1/2/3 (cr-java-0061) + Blocker-4 (cr-java-0062) +
    // Blocker-5/6/7 (cr-java-0063):
    // Hard-coded absolute paths and local FileWriter replaced with GCS bucket
    // name and prefix injected from environment / application.properties.
    // -----------------------------------------------------------------------
    @Value("${gcs.bucket.name:resorts-lite-reports}")
    private String gcsBucketName;

    @Value("${gcs.report.prefix:reports/}")
    private String gcsReportPrefix;

    // -----------------------------------------------------------------------
    // Blocker-12 (cr-java-0077): Hard-coded port replaced with environment
    // variable ${PORT} — Cloud Run / GKE inject this at runtime.
    // -----------------------------------------------------------------------
    @Value("${PORT:8080}")
    private int serverPort;

    // -----------------------------------------------------------------------
    // Blocker-11 (cr-java-0071): Hard-coded report download URL externalised
    // to application property backed by environment variable.
    // -----------------------------------------------------------------------
    @Value("${app.report.download.base-url:https://reports.resorts-internal.com/download}")
    private String reportDownloadBaseUrl;

    /**
     * Generates a monthly CSV report and uploads it to Google Cloud Storage.
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string (e.g. "2024")
     * @return result map containing upload status and GCS object path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // Blocker-1 (cr-java-0061): fileName now used as a GCS object key, not a local path.
        String objectName = gcsReportPrefix + "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Blocker-5/6/7 (cr-java-0063) + Blocker-4 (cr-java-0062):
            // Replace new File() / FileWriter with GCS Storage client write.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            Storage storage = StorageOptions.getDefaultInstance().getService();
            BlobId blobId = BlobId.of(gcsBucketName, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("text/csv")
                    .build();
            storage.create(blobInfo, csvContent.getBytes(StandardCharsets.UTF_8));

            result.put("status", "generated");
            // Blocker-2/3 (cr-java-0061): GCS URI replaces local absolute path.
            result.put("path", "gs://" + gcsBucketName + "/" + objectName);
            // Blocker-12 (cr-java-0077): port sourced from injected env variable.
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a signed or public download URL for a named report stored in GCS.
     *
     * <p>Blocker-11 (cr-java-0071): The base URL is externalised to
     * {@code app.report.download.base-url} so it can differ per environment
     * without code changes.
     *
     * @param reportName the report file name
     * @return fully-qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Blocker-11 (cr-java-0071): base URL injected from environment variable.
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns current system / configuration information.
     *
     * <p>Blocker-19 (cr-java-0111): Replaced server-local {@code new Date()} /
     * {@link java.text.SimpleDateFormat} with a UTC {@link Instant} timestamp to
     * eliminate timezone inconsistencies across distributed cloud instances.
     *
     * @return map of system info entries
     */
    public Map<String, Object> getSystemInfo() {
        // Blocker-19 (cr-java-0111): UTC timestamp — no server-local timezone dependency.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // Blocker-1/2/3 (cr-java-0061): GCS bucket replaces hard-coded local paths.
        info.put("gcsBucket", gcsBucketName);
        info.put("gcsReportPrefix", gcsReportPrefix);
        // Blocker-12 (cr-java-0077): port from env variable.
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
