package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated: replaced legacy java.util.Date + SimpleDateFormat with java.time.LocalDateTime
// and DateTimeFormatter — thread-safe, Java 8+ standard, fully compatible with Java 17
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Updated: Hardcoded absolute path replaced with environment-variable-backed property.
    // /var/legacy/reports does not exist in a Docker container image. Breaks containerisation.
    // Use volume mounts, cloud object storage (S3 / Azure Blob), or environment variable.
    // Fix for: czr-java-001 [Software Portability / Mandatory]
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // Updated: Windows-style absolute backup path removed.
    // C:\ResortBackups\nightly\ fails on any Linux-based container or cloud host.
    // Backup path is now externalised to an environment variable.
    // Fix for: czr-java-001 [Software Portability / Mandatory]
    @Value("${app.backup.path:/tmp/backups/}")
    private String backupPath;

    // Updated: Server port externalised to environment variable.
    // Container orchestration (ECS/EKS) dynamically assigns ports; hardcoded ports
    // prevent dynamic port binding required for modern container deployment.
    // Fix for: czr-port-001 [Software Portability / High]
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    // Updated: Report download base URL externalised to environment variable.
    // Plain HTTP replaced with HTTPS for cloud security compliance.
    // Fix for: cr-java-0088 [Cloud Compatibility / Mandatory]
    @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download/}")
    private String reportDownloadBaseUrl;

    /**
     * Generates a monthly booking report CSV file.
     *
     * @param month the month identifier (e.g. "03")
     * @param year  the four-digit year (e.g. "2024")
     * @return a map containing the generation status and file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // Updated: reportBasePath is now injected from environment variable.
        // Fix for: czr-java-001 [Software Portability / Mandatory]
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
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report file.
     * Updated: URL base is now injected from environment variable; HTTPS enforced.
     * Fix for: cr-java-0088 [Cloud Compatibility / Mandatory]
     *
     * @param reportName the report file name
     * @return the full download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including report paths and current timestamp.
     * Updated: uses java.time.LocalDateTime + DateTimeFormatter (thread-safe, Java 17 compatible)
     * instead of legacy java.util.Date + SimpleDateFormat.
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // Updated: java.time.LocalDateTime replaces java.util.Date (deprecated for Java 17)
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
