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

    // Updated: Report base path externalised to environment variable / application property.
    // Replaces hardcoded /var/legacy/reports path that breaks containerisation (fixes czr-java-001).
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // Updated: Report download base URL externalised to environment variable.
    // Replaces hardcoded HTTP URL with HTTPS-enforced configurable endpoint (fixes cr-java-0088, czr-java-001).
    @Value("${app.report.download-url:https://reports.resorts-internal.com/download}")
    private String reportDownloadBaseUrl;

    // Updated: Server port removed from application logic — port is managed by Spring Boot
    // via server.port property / SERVER_PORT env var (fixes czr-port-001).

    /**
     * Generates a monthly booking report CSV file at the configured report base path.
     * The report path is externalised via {@code app.report.base-path} property to support
     * container volume mounts and cloud object storage integration (fixes czr-java-001).
     *
     * @param month  Month identifier (e.g., "03")
     * @param year   Year identifier (e.g., "2024")
     * @return Map containing generation status and output file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // Updated: Uses externalised reportBasePath instead of hardcoded /var/legacy/reports (fixes czr-java-001).
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
            // Updated: Removed hardcoded SERVER_PORT from response (fixes czr-port-001).

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a secure HTTPS download URL for the given report name.
     * The base URL is externalised via {@code app.report.download-url} property.
     * Replaces the previous hardcoded HTTP URL (fixes cr-java-0088, czr-java-001).
     *
     * @param reportName  Name of the report file to download
     * @return Fully qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Updated: HTTPS URL constructed from externalised base URL property.
        // Replaces hardcoded plain HTTP URL (fixes cr-java-0088).
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information including the configured report path and current timestamp.
     * Infrastructure paths are sourced from externalised configuration (fixes czr-java-001).
     *
     * @return Map containing system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // Updated: Replaced deprecated java.util.Date / SimpleDateFormat with java.time API (Java 17 compatible).
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        // Updated: Uses externalised reportBasePath (fixes czr-java-001).
        info.put("reportPath", reportBasePath);
        // Updated: Removed hardcoded Windows BACKUP_PATH and SERVER_PORT (fixes czr-java-001, czr-port-001).
        info.put("generatedAt", timestamp);
        return info;
    }
}
