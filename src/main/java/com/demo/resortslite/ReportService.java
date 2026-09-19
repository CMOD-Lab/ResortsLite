package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated: Replaced deprecated java.util.Date + SimpleDateFormat with java.time.LocalDateTime
// and DateTimeFormatter — fully supported in Java 8+ and Java 21 (java.time API, JSR-310)
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Fixed czr-java-001: Hardcoded absolute paths ("/var/legacy/reports/" and
    // "C:\\ResortBackups\\nightly\\") have been replaced with configurable values
    // read from environment variables / application.properties.
    // This makes the service portable across Linux containers, Windows dev machines,
    // and cloud-native environments (ECS/EKS) where OS paths are not guaranteed.
    @Value("${app.report.base-path:reports}")
    private String reportBasePath;

    @Value("${app.report.backup-path:backups}")
    private String backupPath;

    // Fixed czr-port-001: Removed hardcoded SERVER_PORT constant (8080).
    // The server port is now managed exclusively by Spring Boot via the
    // server.port property (or SERVER_PORT environment variable), allowing
    // container orchestrators (ECS/EKS) to assign ports dynamically.

    // Fixed cr-java-0088: Report download base URL is externalised to an
    // environment variable so HTTPS can be enforced in cloud environments.
    @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download}")
    private String reportDownloadBaseUrl;

    /**
     * Generates a monthly CSV report and writes it to the configured report directory.
     *
     * @param month the month identifier (e.g. "03")
     * @param year  the year identifier (e.g. "2024")
     * @return a map containing the generation status and output path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // Fixed czr-java-001: fullPath now uses the configurable reportBasePath
        // instead of the hardcoded "/var/legacy/reports/" absolute path.
        String fullPath = reportBasePath + File.separator + fileName;

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
            // Fixed czr-port-001: serverPort is no longer hardcoded; removed from response.

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report file.
     * Fixed cr-java-0088: URL base is now read from an environment variable,
     * allowing HTTPS to be enforced in cloud/production environments.
     *
     * @param reportName the name of the report file
     * @return the full download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information including configured paths and current timestamp.
     * Updated: Uses java.time.LocalDateTime + DateTimeFormatter instead of deprecated
     * java.util.Date and SimpleDateFormat for Java 21 compatibility.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Updated: java.util.Date + SimpleDateFormat → java.time.LocalDateTime + DateTimeFormatter
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        // Fixed czr-java-001: paths are now configurable, not hardcoded OS-specific strings.
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        // Fixed czr-port-001: serverPort constant removed; port is managed by Spring Boot.
        info.put("generatedAt", timestamp);
        return info;
    }
}
