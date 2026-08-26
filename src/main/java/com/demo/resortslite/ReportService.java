package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated: java.util.Date + SimpleDateFormat replaced with java.time API
// Reason: java.util.Date and SimpleDateFormat are not thread-safe and are
// considered legacy since Java 8. Java 21 best practice is to use the
// java.time package (JSR-310): LocalDateTime + DateTimeFormatter are
// immutable, thread-safe, and have a cleaner API.
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Updated: Hardcoded absolute path /var/legacy/reports replaced with an
    // environment-variable-backed configurable value (czr-java-001).
    // Defaults to the JVM temp directory so the application works out-of-the-box
    // in any container without requiring a pre-existing host path.
    // In production, point REPORT_BASE_PATH to a mounted volume or use cloud
    // object storage (S3 / Azure Blob) instead of the local filesystem.
    @Value("${app.report.base-path:${java.io.tmpdir}/reports}")
    private String reportBasePath;

    // Updated: Windows-style hardcoded backup path removed (czr-java-001).
    // Backup destination is now configurable via environment variable so it
    // works on Linux-based containers and cloud hosts without modification.
    @Value("${app.backup.path:${java.io.tmpdir}/backups}")
    private String backupPath;

    // Updated: Hardcoded server port removed from application logic (czr-port-001).
    // Port is now injected from application.properties / environment variable so
    // container orchestration (ECS/EKS) can assign it dynamically.
    @Value("${server.port:8080}")
    private int serverPort;

    // Updated: Report download base URL externalised to environment variable.
    // Plain HTTP replaced with HTTPS default to comply with cloud security
    // standards (cr-java-0088). Override via REPORT_DOWNLOAD_BASE_URL env var.
    @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download}")
    private String reportDownloadBaseUrl;

    /**
     * Generates a monthly booking report as a CSV file.
     *
     * @param month the month (e.g. "03")
     * @param year  the year  (e.g. "2024")
     * @return a map containing the generation status and output file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = reportBasePath + "/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Updated: Uses injected reportBasePath instead of hardcoded
            // /var/legacy/reports (czr-java-001).
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
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report file.
     *
     * <p>Updated: Plain HTTP URL replaced with an HTTPS-defaulting, environment-
     * configurable base URL (cr-java-0088 / czr-java-001). The base URL is
     * injected via {@code app.report.download-base-url} so it can be overridden
     * per environment without code changes.</p>
     *
     * @param reportName the report file name
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // Updated: Hardcoded plain-HTTP URL replaced with injected HTTPS base URL
        // (cr-java-0088). Override app.report.download-base-url in application.properties
        // or via environment variable for each deployment environment.
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information including configured paths, port, and current timestamp.
     *
     * <p>Updated: java.util.Date + SimpleDateFormat replaced with java.time.LocalDateTime
     * and DateTimeFormatter (thread-safe, Java 21 recommended date/time API).</p>
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Updated: java.util.Date + SimpleDateFormat replaced with java.time.LocalDateTime
        // and DateTimeFormatter (thread-safe, Java 21 recommended date/time API)
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
