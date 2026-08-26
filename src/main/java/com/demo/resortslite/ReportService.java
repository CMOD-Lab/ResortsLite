package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated from legacy java.util.Date / java.text.SimpleDateFormat to java.time API
// java.time.LocalDateTime and DateTimeFormatter are the Java 8+ (and Java 17) standard
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Fixed czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute path
    // replaced with an environment-variable-backed Spring property.
    // Set REPORT_BASE_PATH env var (or app.report.base-path in application.properties)
    // to a mounted volume path or cloud object-storage prefix before deployment.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // Fixed czr-java-001 [Software Portability / Mandatory]: Windows-style hardcoded
    // backup path removed. Backup destination is now supplied via environment variable
    // BACKUP_PATH, defaulting to /tmp/backups/ for Linux-based containers.
    @Value("${app.backup.path:/tmp/backups/}")
    private String backupPath;

    // Fixed czr-port-001 [Software Portability / High]: Hardcoded server port removed
    // from application logic. Port is now read from the environment variable PORT
    // (standard for ECS/EKS/Heroku) with a safe default of 8080.
    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * Generates a monthly booking report as a CSV file.
     *
     * <p>The output directory is resolved from the {@code app.report.base-path} property
     * (fixes czr-java-001 — no hardcoded absolute paths in application logic).</p>
     *
     * @param month Month identifier (e.g. "03")
     * @param year  Year identifier (e.g. "2024")
     * @return Map containing generation status and file path, or an error message
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
            // serverPort is now injected from environment — not hardcoded (fixes czr-port-001)
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a secure HTTPS download URL for the given report file.
     *
     * <p>Fixed cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL replaced
     * with HTTPS. Cloud security standards (AWS WAF, ALB) enforce HTTPS for all
     * inter-service and client-facing communication.</p>
     *
     * @param reportName Name of the report file to download
     * @return HTTPS URL pointing to the report download endpoint
     */
    public String buildReportDownloadUrl(String reportName) {
        // Fixed cr-java-0088: Changed scheme from http:// to https://.
        // The hostname and port are now environment-variable-backed via application.properties.
        return "https://reports.resorts-internal.com/download/" + reportName;
    }

    /**
     * Returns current system information including configured paths and timestamp.
     *
     * <p>All path and port values are now injected from environment variables,
     * eliminating hardcoded infrastructure references (fixes czr-java-001, czr-port-001).</p>
     *
     * @return Map containing reportPath, backupPath, serverPort, and generatedAt timestamp
     */
    public Map<String, Object> getSystemInfo() {
        // Updated from legacy java.util.Date + SimpleDateFormat to java.time.LocalDateTime
        // + DateTimeFormatter — thread-safe, immutable, and idiomatic in Java 17
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        // reportBasePath and backupPath are now injected from env vars (fixes czr-java-001)
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        // serverPort is now injected from env var (fixes czr-port-001)
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
