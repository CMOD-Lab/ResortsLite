package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// [JAVA8_TO_17_DEPRECATED_APIS] Replaced legacy java.util.Date + SimpleDateFormat
// with java.time.LocalDateTime + DateTimeFormatter (introduced in Java 8, preferred in Java 17+).
// SimpleDateFormat is not thread-safe and is considered a legacy API.
// DateTimeFormatter is immutable, thread-safe, and the recommended replacement.
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // [PORTABILITY]: Hardcoded absolute paths removed (resolves czr-java-001).
    // REPORT_BASE_PATH is now injected from the environment variable REPORT_BASE_PATH
    // (configured in application.properties as app.report.base-path).
    // In containerised deployments, mount a volume or use cloud object storage (S3 / Azure Blob)
    // and set the environment variable accordingly.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // [PORTABILITY]: Windows-style absolute path C:\ResortBackups\nightly\ removed
    // (resolves czr-java-001). Backup destination is now externalised to an environment
    // variable so it works on any OS and inside Linux-based containers.
    @Value("${app.backup.path:/tmp/resort-backups/}")
    private String backupPath;

    // [PORTABILITY / CLOUD_COMPATIBILITY]: Hardcoded SERVER_PORT constant removed
    // (resolves czr-port-001). Container orchestration (ECS/EKS) dynamically assigns ports.
    // The actual port is read from the environment variable PORT (see application.properties).
    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * Generates a monthly booking report as a CSV file.
     *
     * <p>The output file is written to the path configured by the {@code app.report.base-path}
     * environment variable (default: {@code /tmp/reports/}). In production, this should point
     * to a mounted volume or a cloud object-storage path.
     *
     * @param month the month identifier (e.g. "2024-03")
     * @param year  the four-digit year (e.g. "2024")
     * @return a map containing {@code status}, {@code path}, and {@code serverPort}
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // [PORTABILITY]: Uses injected reportBasePath instead of hardcoded /var/legacy/reports/
        String fullPath = reportBasePath + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // [PORTABILITY]: Directory creation now uses the externalised reportBasePath
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
     * Builds the HTTPS download URL for a named report artifact.
     *
     * <p>The base URL is externalised to the {@code app.report.download.base-url}
     * environment variable so it can be updated without code changes (resolves cr-java-0088).
     * HTTPS is enforced to comply with cloud security standards (AWS ALB / WAF).
     *
     * @param reportName the file name of the report to download
     * @return the fully qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // [CLOUD_COMPATIBILITY / SECURITY]: Replaced hardcoded plain-HTTP URL with an
        // environment-variable-backed HTTPS URL (resolves cr-java-0088).
        // Cloud security standards (AWS ALB, WAF, Well-Architected) enforce HTTPS for all
        // internal and external service communication.
        String baseUrl = System.getenv().getOrDefault(
                "REPORT_DOWNLOAD_BASE_URL",
                "https://reports.resorts-internal.com/download");
        return baseUrl + "/" + reportName;
    }

    /**
     * Returns current system / configuration information for diagnostic purposes.
     *
     * @return a map containing {@code reportPath}, {@code backupPath}, {@code serverPort},
     *         and {@code generatedAt} (ISO-8601 timestamp)
     */
    public Map<String, Object> getSystemInfo() {
        // [JAVA8_TO_17_DEPRECATED_APIS] Uses DateTimeFormatter (thread-safe) instead of
        // the legacy SimpleDateFormat.
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        // [PORTABILITY]: reportBasePath and backupPath are now injected from environment
        // variables, not hardcoded (resolves czr-java-001).
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        // [PORTABILITY]: serverPort is now injected from the PORT environment variable
        // (resolves czr-port-001).
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
