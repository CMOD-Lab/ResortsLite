package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// -----------------------------------------------------------------------
// FIXED (issue-8): Replaced legacy java.util.Date / SimpleDateFormat with
// java.time API (java.time.LocalDateTime + DateTimeFormatter).
// java.util.Date and SimpleDateFormat are not thread-safe and are
// effectively deprecated in Java 17. The java.time package (JSR-310)
// is the idiomatic replacement introduced in Java 8 and preferred in 17.
// -----------------------------------------------------------------------
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // -----------------------------------------------------------------------
    // FIXED (czr-java-001): Removed hardcoded absolute paths (/var/legacy/reports
    // and C:\ResortBackups\nightly\). Paths are now injected from environment
    // variables / application properties, enabling container and cloud portability.
    // -----------------------------------------------------------------------
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // -----------------------------------------------------------------------
    // FIXED (czr-port-001): Removed hardcoded SERVER_PORT constant.
    // Port is now read from the Spring environment, allowing container
    // orchestration (ECS/EKS) to assign ports dynamically.
    // -----------------------------------------------------------------------
    @Value("${server.port:8080}")
    private int serverPort;

    // -----------------------------------------------------------------------
    // FIXED (cr-java-0088): Removed hardcoded report download base URL.
    // Externalised to environment variable for cloud/container portability
    // and to enforce HTTPS in production environments.
    // -----------------------------------------------------------------------
    @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download}")
    private String reportDownloadBaseUrl;

    /**
     * Generates a monthly booking report CSV file.
     *
     * @param month the month (e.g. "03")
     * @param year  the year  (e.g. "2024")
     * @return a result map containing status and file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // FIXED (czr-java-001): Uses injected reportBasePath instead of hardcoded
        // /var/legacy/reports — compatible with Docker volumes and cloud storage mounts.
        String fullPath = reportBasePath + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(reportBasePath);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            FileWriter writer = new FileWriter(fullPath);
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.close();

            result.put("status", "generated");
            result.put("path", fullPath);
            // FIXED (czr-port-001): serverPort is now injected, not hardcoded.
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report.
     * FIXED (cr-java-0088): Uses HTTPS base URL injected from environment,
     * replacing the hardcoded plain-HTTP URL.
     *
     * @param reportName the report file name
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information including report paths and current timestamp.
     * FIXED (issue-8): Uses java.time.LocalDateTime + DateTimeFormatter instead of
     * the legacy java.util.Date / SimpleDateFormat (not thread-safe, deprecated in Java 17).
     *
     * @return a map of system info key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // FIXED: java.time.LocalDateTime and DateTimeFormatter are thread-safe and
        // idiomatic in Java 17, replacing the legacy java.util.Date / SimpleDateFormat.
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> info = new HashMap<>();
        // FIXED (czr-java-001): reportBasePath is now injected, not hardcoded.
        info.put("reportPath", reportBasePath);
        // FIXED (czr-java-001): backupPath removed — Windows-style path replaced by
        // environment-driven configuration (app.report.base-path).
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
