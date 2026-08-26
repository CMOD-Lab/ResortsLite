package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Service responsible for generating and managing resort booking reports.
 *
 * <p>All file-system paths, server ports, and external URLs are resolved from
 * environment variables so the application runs portably across local, container,
 * and cloud environments without code changes.</p>
 */
@Service
public class ReportService {

    // -------------------------------------------------------------------------
    // Externalised configuration — resolved from environment variables at startup.
    // Hardcoded absolute paths (Linux and Windows) have been removed.
    // -------------------------------------------------------------------------

    /**
     * Base directory for generated report files.
     * Resolved from {@code REPORT_BASE_PATH}; falls back to the JVM temp directory
     * so the application starts without manual configuration in development.
     */
    private static final String REPORT_BASE_PATH = System.getenv().getOrDefault(
            "REPORT_BASE_PATH",
            System.getProperty("java.io.tmpdir") + "/reports");

    /**
     * Base directory for report backup files.
     * Resolved from {@code BACKUP_PATH}; falls back to the JVM temp directory.
     */
    private static final String BACKUP_PATH = System.getenv().getOrDefault(
            "BACKUP_PATH",
            System.getProperty("java.io.tmpdir") + "/backups");

    /**
     * HTTP server port.
     * Resolved from {@code SERVER_PORT} to support dynamic port assignment by
     * container orchestrators (ECS / EKS).
     */
    private static final int SERVER_PORT = Integer.parseInt(
            System.getenv().getOrDefault("SERVER_PORT", "8080"));

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates a monthly booking report CSV file for the specified month and year.
     *
     * @param month the month (e.g., {@code "03"})
     * @param year  the four-digit year (e.g., {@code "2024"})
     * @return a map containing the generation status and output file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = REPORT_BASE_PATH + File.separator + fileName;

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
     * Builds the HTTPS download URL for a named report file.
     *
     * <p>The base URL is resolved from the {@code REPORT_DOWNLOAD_BASE_URL}
     * environment variable so it can be configured per environment without code
     * changes. Updated from plain HTTP to HTTPS to enforce transport-layer
     * encryption.</p>
     *
     * @param reportName the name of the report file
     * @return the fully-qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Updated to HTTPS; base URL externalised to environment variable.
        String baseUrl = System.getenv().getOrDefault(
                "REPORT_DOWNLOAD_BASE_URL", "https://reports.resorts-internal.com/download");
        return baseUrl + "/" + reportName;
    }

    /**
     * Returns system information including configured paths, port, and current timestamp.
     *
     * <p>Uses {@link java.time.LocalDateTime} and {@link DateTimeFormatter} from the
     * modern {@code java.time} API, replacing the legacy {@code java.util.Date} and
     * {@code SimpleDateFormat} classes that were not thread-safe.</p>
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // java.time.LocalDateTime replaces legacy java.util.Date / SimpleDateFormat.
        // DateTimeFormatter is immutable and thread-safe; SimpleDateFormat was not.
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
