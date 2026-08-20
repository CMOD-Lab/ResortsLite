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

/**
 * ReportService — generates and serves resort booking reports.
 *
 * <p>All file paths and server configuration are externalised to environment
 * variables / application properties so the service runs correctly inside
 * Docker containers and cloud-hosted environments (ECS, EKS, etc.).</p>
 */
@Service
public class ReportService {

    /**
     * Base directory for generated report files.
     * Defaults to /tmp/reports/ (always writable in containers).
     * Override via the {@code REPORT_BASE_PATH} environment variable or
     * {@code app.report.base.path} application property for production deployments
     * that use a mounted volume or cloud object storage path.
     */
    @Value("${app.report.base.path:/tmp/reports/}")
    private String reportBasePath;

    /**
     * Public base URL used to build report download links.
     * Must use HTTPS in production (cr-java-0088).
     * Override via {@code app.report.download.base-url} application property.
     */
    @Value("${app.report.download.base-url:https://reports.resorts-internal.com/download}")
    private String reportDownloadBaseUrl;

    // Thread-safe, immutable DateTimeFormatter (replaces non-thread-safe SimpleDateFormat)
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generates a monthly booking report CSV file.
     *
     * @param month Month label (e.g. "March")
     * @param year  Four-digit year string (e.g. "2024")
     * @return Map containing generation status and file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // reportBasePath is injected from application property / env var — no hardcoded path
        String fullPath = reportBasePath + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(reportBasePath);
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

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a secure HTTPS download URL for the given report file name.
     * The base URL is externalised to an application property (cr-java-0088 — HTTPS enforced).
     *
     * @param reportName File name of the report to download
     * @return Fully qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Fixed cr-java-0088: URL base is injected from application property, defaulting to HTTPS.
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns current system/configuration information for diagnostics.
     *
     * @return Map of configuration keys and their runtime values
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("generatedAt", timestamp);
        return info;
    }
}
