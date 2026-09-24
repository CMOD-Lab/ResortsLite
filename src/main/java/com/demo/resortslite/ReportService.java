package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated from java.util.Date / java.text.SimpleDateFormat to java.time API
// (JAVA8_TO_21_DATE_TIME_CHANGES): Legacy date/time APIs replaced with java.time for
// thread safety and Java 17 best practices. SimpleDateFormat is not thread-safe;
// DateTimeFormatter is immutable and thread-safe.
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIX czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute paths removed.
    // Report base path is now externalised to an environment variable / application.properties
    // so it resolves correctly inside Docker containers, ECS tasks, and cloud environments.
    // In production, set REPORT_BASE_PATH to an S3-mounted volume or object-storage path.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // FIX czr-java-001: Windows-style hardcoded backup path removed.
    // Backup destination is now externalised to an environment variable.
    @Value("${app.backup.path:/tmp/backups/}")
    private String backupPath;

    // FIX czr-port-001 [Software Portability / High]: Hardcoded server port removed from
    // application logic. Port is now read from the environment / application.properties
    // so container orchestration (ECS / EKS) can assign it dynamically.
    @Value("${server.port:8080}")
    private int serverPort;

    // Thread-safe DateTimeFormatter (replaces non-thread-safe SimpleDateFormat)
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generates a monthly booking report CSV file.
     *
     * <p>FIX czr-java-001: Report directory is resolved from the externalised
     * {@code app.report.base-path} property rather than a hardcoded absolute path.</p>
     *
     * @param month Month identifier (e.g. "03").
     * @param year  Year identifier (e.g. "2024").
     * @return Map containing generation status and output file path.
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
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
            // FIX czr-port-001: serverPort now sourced from injected property, not a
            // hardcoded constant.
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a secure HTTPS download URL for a named report file.
     *
     * <p>FIX cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL replaced with
     * HTTPS. Cloud security standards (AWS WAF, ALB policies) enforce HTTPS for all
     * internal and external service communication.</p>
     *
     * @param reportName File name of the report to download.
     * @return HTTPS download URL string.
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIX cr-java-0088: Changed scheme from http:// to https:// to comply with
        // cloud security standards and AWS Well-Architected Framework requirements.
        return "https://reports.resorts-internal.com/download/" + reportName;
    }

    /**
     * Returns current system / environment information for diagnostics.
     *
     * <p>FIX czr-java-001, czr-port-001: All values are now sourced from injected
     * properties rather than hardcoded constants.</p>
     *
     * @return Map of diagnostic key-value pairs.
     */
    public Map<String, Object> getSystemInfo() {
        // Updated from new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
        // to java.time.LocalDateTime (JAVA8_TO_21_DATE_TIME_CHANGES):
        // - Thread-safe: DateTimeFormatter is immutable, SimpleDateFormat is not
        // - No legacy java.util.Date dependency
        // - Cleaner API aligned with Java 8+ best practices
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
