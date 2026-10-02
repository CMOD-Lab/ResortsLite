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
 * Service for generating and managing resort reports.
 * Updated for Java 21 / Spring Boot 3 compatibility:
 * - Replaced legacy java.util.Date and SimpleDateFormat with java.time.LocalDateTime
 *   and java.time.format.DateTimeFormatter (available since Java 8, preferred in Java 21).
 * - Externalised hardcoded filesystem paths to environment variables.
 * - Replaced hardcoded HTTP report URL with HTTPS and environment-variable-driven base URL.
 */
@Service
public class ReportService {

    // Externalised from hardcoded /var/legacy/reports/ — use environment variable or
    // cloud object storage (S3 / Azure Blob) in production.
    private static final String REPORT_BASE_PATH =
            System.getenv().getOrDefault("REPORT_BASE_PATH", "/tmp/reports");

    // Externalised from hardcoded Windows path C:\ResortBackups\nightly\
    private static final String BACKUP_PATH =
            System.getenv().getOrDefault("BACKUP_PATH", "/tmp/resort-backups");

    // Server port externalised — container orchestration (ECS/EKS) assigns ports dynamically.
    private static final int SERVER_PORT =
            Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));

    // DateTimeFormatter is thread-safe (unlike SimpleDateFormat) and part of java.time API.
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generates a monthly booking report as a CSV file.
     *
     * @param month the month (e.g., "03")
     * @param year  the year (e.g., "2024")
     * @return a map containing the generation status and file path
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
     * Builds the download URL for a given report file.
     * Uses HTTPS and an environment-variable-driven base URL for cloud compatibility.
     *
     * @param reportName the name of the report file
     * @return the full HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Replaced hardcoded HTTP URL with HTTPS and environment-variable-driven base URL.
        String baseUrl = System.getenv().getOrDefault(
                "REPORT_DOWNLOAD_BASE_URL", "https://reports.resorts-internal.com/download");
        return baseUrl + "/" + reportName;
    }

    /**
     * Returns system information including report paths and current timestamp.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Replaced java.util.Date + SimpleDateFormat with java.time.LocalDateTime +
        // DateTimeFormatter — thread-safe and idiomatic in Java 21.
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);

        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
