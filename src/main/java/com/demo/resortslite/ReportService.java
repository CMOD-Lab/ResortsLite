package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated: java.util.Date / SimpleDateFormat replaced with java.time API
// (JAVA8_TO_21_DATE_TIME_CHANGES: migrate from legacy date/time APIs to java.time)
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // czr-java-001: Hardcoded absolute paths removed.
    // Report base path and download URL are now externalised to environment variables
    // so the application works correctly inside Docker containers, ECS tasks, and EKS pods
    // without any OS-level path assumptions.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // czr-java-001: Windows-style absolute backup path removed.
    // Backup destination is now externalised to an environment variable.
    @Value("${app.backup.path:/tmp/backups/}")
    private String backupPath;

    // czr-port-001: Hardcoded server port removed from application logic.
    // The port is managed by Spring Boot via server.port (environment variable SERVER_PORT).
    // Injected here only if needed for URL construction; defaults to 8080.
    @Value("${server.port:8080}")
    private int serverPort;

    // cr-java-0088: Report download base URL externalised to environment variable.
    // HTTPS is enforced; no hardcoded plain-HTTP URLs in source code.
    @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download/}")
    private String reportDownloadBaseUrl;

    /**
     * Generates a monthly CSV report and writes it to the configured report directory.
     *
     * @param month the month identifier (e.g. "03")
     * @param year  the four-digit year (e.g. "2024")
     * @return a map containing the generation status and output path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // czr-java-001: Path constructed from environment-variable-backed base path.
        String fullPath = reportBasePath + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // czr-java-001: Directory created under the configurable base path.
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
            // czr-port-001: serverPort is now injected from Spring environment, not hardcoded.
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
     * @param reportName the report file name
     * @return the full download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0088: Plain HTTP URL replaced with environment-variable-backed HTTPS base URL.
        // No hardcoded hostnames or ports in source code.
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including configured paths, port, and current timestamp.
     * Updated to use java.time.LocalDateTime instead of legacy java.util.Date
     * (JAVA8_TO_21_DATE_TIME_CHANGES).
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Updated: replaced new SimpleDateFormat(...).format(new Date()) with java.time API
        // (JAVA8_TO_21_DATE_TIME_CHANGES: migrate from legacy date/time APIs to java.time)
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> info = new HashMap<>();
        // czr-java-001: Paths are now sourced from environment variables, not hardcoded.
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        // czr-port-001: Port is injected from Spring environment.
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
