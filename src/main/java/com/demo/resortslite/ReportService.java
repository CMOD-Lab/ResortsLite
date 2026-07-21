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
 * Service for generating and serving booking reports.
 *
 * <p>Migration notes (Java 1.8 → Java 21 / Spring Boot 2.7.x → 3.2.x):
 * <ul>
 *   <li>Fix (Hardcoded Absolute File Paths): All paths resolved from environment
 *       variables so container deployments can mount the correct volumes.</li>
 *   <li>Fix (Legacy java.util.Date / SimpleDateFormat): Replaced with
 *       {@link java.time.LocalDateTime} and {@link java.time.format.DateTimeFormatter}
 *       which are thread-safe and fully supported in Java 21.</li>
 *   <li>Fix (Plain HTTP URLs): Report download URL uses HTTPS.</li>
 * </ul>
 */
@Service
public class ReportService {

    /**
     * Base path for report files. Resolved from the REPORT_BASE_PATH environment
     * variable so that container deployments can mount the correct volume.
     * Fix: Hardcoded Absolute File Paths — now environment-variable-driven.
     */
    private static final String REPORT_BASE_PATH =
            System.getenv().getOrDefault("REPORT_BASE_PATH", "/var/reports/");

    /**
     * Backup path resolved from the BACKUP_PATH environment variable.
     * Fix: Hardcoded Absolute File Paths — avoids OS-specific paths that break
     * on Linux-based containers.
     */
    private static final String BACKUP_PATH =
            System.getenv().getOrDefault("BACKUP_PATH", "/var/backups/");

    /**
     * Server port resolved from the SERVER_PORT environment variable so that
     * container orchestration (ECS / EKS) can assign ports dynamically.
     */
    private static final int SERVER_PORT =
            Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));

    /**
     * Date-time formatter using the thread-safe java.time API.
     * Fix: Legacy java.util.Date / SimpleDateFormat — replaced with DateTimeFormatter.
     */
    private static final DateTimeFormatter REPORT_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generates a monthly booking report CSV and writes it to the configured
     * report directory.
     *
     * @param month the month identifier (e.g. "2024-03")
     * @param year  the four-digit year string
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
     * Builds a secure HTTPS download URL for the given report name.
     * Fix: Plain HTTP URLs — all report download URLs now use HTTPS.
     *
     * @param reportName the name of the report file
     * @return the full HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Fix: Plain HTTP URLs for Internal Service Calls — HTTPS enforced.
        // Host resolved from environment variable for cloud portability.
        String reportHost = System.getenv().getOrDefault(
                "REPORT_HOST", "reports.resorts-internal.com");
        return "https://" + reportHost + "/download/" + reportName;
    }

    /**
     * Returns system information including configured paths and the current timestamp.
     * Fix: Legacy java.util.Date / SimpleDateFormat — uses java.time.LocalDateTime
     * and DateTimeFormatter (thread-safe, Java 21 compatible).
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Fix: Legacy SimpleDateFormat replaced with thread-safe DateTimeFormatter (Java 8+).
        String timestamp = LocalDateTime.now().format(REPORT_TIMESTAMP_FORMAT);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
