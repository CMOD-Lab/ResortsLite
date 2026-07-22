package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated from java.util.Date / java.text.SimpleDateFormat to java.time API (JAVA8_TO_21_DATE_TIME_CHANGES)
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // NOTE: Report base path should be externalised to an environment variable or
    // cloud object storage (e.g., S3 / Azure Blob) for container-based deployments.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/";

    // Updated: Replaced Windows-style path with Linux-compatible path for PostgreSQL/container deployments
    private static final String BACKUP_PATH = "/var/backups/resort/nightly/";

    // NOTE: Server port should be managed by the container orchestrator (ECS/EKS).
    // Externalise via server.port in application.properties or an environment variable.
    private static final int SERVER_PORT = 8080;

    /**
     * Generates a monthly CSV report and writes it to the configured report directory.
     *
     * @param month the month (e.g., "03")
     * @param year  the year  (e.g., "2024")
     * @return a map containing the generation status and file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = REPORT_BASE_PATH + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(REPORT_BASE_PATH);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            try (FileWriter writer = new FileWriter(fullPath)) {
                writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
                writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
                writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            }

            result.put("status", "generated");
            result.put("path", fullPath);
            result.put("serverPort", SERVER_PORT);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report.
     * NOTE: URL scheme should be HTTPS and the hostname externalised to configuration
     * for cloud-native deployments.
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    /**
     * Returns basic system information including the current timestamp.
     *
     * @return a map of system info key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Updated from java.util.Date / SimpleDateFormat to java.time.LocalDateTime (JAVA8_TO_21_DATE_TIME_CHANGES)
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
