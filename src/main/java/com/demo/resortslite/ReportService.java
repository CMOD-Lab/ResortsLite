package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // czr-java-001 fix: Hardcoded absolute paths replaced with environment-variable-backed
    // Spring @Value properties. The default falls back to /tmp/reports/ which is available
    // inside Docker/container environments. Production deployments should set REPORT_BASE_PATH
    // to a mounted volume or cloud object-storage path (S3 / Azure Blob).
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // czr-java-001 fix: Windows-style hardcoded backup path removed.
    // Backup destination is now externalised to an environment variable.
    @Value("${app.backup.path:/tmp/backups/}")
    private String backupPath;

    // czr-port-001 fix: Hardcoded SERVER_PORT constant removed from application logic.
    // Port is now managed exclusively via server.port in application.properties /
    // environment variable SERVER_PORT, allowing dynamic assignment by ECS/EKS.

    // Updated: Use thread-safe DateTimeFormatter (java.time API) instead of SimpleDateFormat
    // (JAVA8_TO_17_DEPRECATED_API_UPDATES) — DateTimeFormatter is immutable and thread-safe
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generates a monthly report CSV file for the given month and year.
     *
     * @param month the month for which the report is generated
     * @param year  the year for which the report is generated
     * @return a map containing the report generation status and file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // czr-java-001 fix: reportBasePath is now injected from environment variable,
        // not hardcoded to /var/legacy/reports/.
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
            // czr-port-001 fix: SERVER_PORT constant removed; port no longer exposed here.

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a given report name.
     *
     * @param reportName the name of the report file
     * @return the full download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0088 fix: Replaced plain HTTP URL with HTTPS to comply with cloud
        // security standards (AWS ALB / WAF enforce HTTPS; plain HTTP is blocked/flagged).
        return "https://reports.resorts-internal.com/download/" + reportName;
    }

    /**
     * Returns system information including report paths, backup path,
     * and the current timestamp.
     *
     * @return a map containing system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Updated: Replaced new SimpleDateFormat(...).format(new Date()) with
        // LocalDateTime.now().format(DateTimeFormatter) — java.time API is the
        // modern, thread-safe replacement for legacy java.util.Date / SimpleDateFormat
        // (JAVA8_TO_17_DEPRECATED_API_UPDATES)
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        Map<String, Object> info = new HashMap<>();
        // czr-java-001 fix: paths now reflect environment-variable-backed values.
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        // czr-port-001 fix: hardcoded SERVER_PORT removed from system info response.
        info.put("generatedAt", timestamp);
        return info;
    }
}
