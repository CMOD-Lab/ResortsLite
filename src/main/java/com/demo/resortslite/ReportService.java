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

    // GCS bucket name and server port are now externalised to environment variables /
    // application.properties — no hard-coded file paths or port numbers remain.
    @Value("${gcs.report.bucket:${GCS_REPORT_BUCKET:resorts-reports-bucket}}")
    private String reportBucket;

    // blocker-12 (cr-java-0077): port externalised via environment variable / property
    @Value("${server.port:${PORT:8080}}")
    private int serverPort;

    // blocker-11 (cr-java-0071): report download base URL externalised via environment variable
    @Value("${app.report.download.url:${REPORT_DOWNLOAD_URL:https://reports.resorts-internal.com/download}}")
    private String reportDownloadBaseUrl;

    @Autowired
    private Storage gcsStorage;

    /**
     * Generates a monthly CSV report and uploads it to Google Cloud Storage.
     * Replaces the previous local-filesystem write to /var/legacy/reports/ (blocker-1,
     * blocker-2, blocker-3, blocker-4, blocker-5, blocker-6, blocker-7).
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // blocker-1/2/3 (cr-java-0061): object name replaces hard-coded absolute path
        String objectName = "monthly/" + year + "/" + month + "/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // blocker-4/5/6/7 (cr-java-0062, cr-java-0063): write to GCS instead of local File
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            BlobId blobId = BlobId.of(reportBucket, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("text/csv")
                    .build();
            gcsStorage.create(blobInfo, csvContent.getBytes(StandardCharsets.UTF_8));

            result.put("status", "generated");
            result.put("gcsBucket", reportBucket);
            result.put("gcsObject", objectName);
            // blocker-12 (cr-java-0077): serverPort now read from injected property
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the externalised base URL.
     * blocker-11 (cr-java-0071): hard-coded URL replaced with injected property.
     */
    public String buildReportDownloadUrl(String reportName) {
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information using UTC timestamps and externalised configuration.
     * blocker-19 (cr-java-0111): replaced server-local Date/SimpleDateFormat with UTC Instant.
     */
    public Map<String, Object> getSystemInfo() {
        // blocker-19 (cr-java-0111): standardise on UTC — no server-local timezone dependency
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        info.put("gcsBucket", reportBucket);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
