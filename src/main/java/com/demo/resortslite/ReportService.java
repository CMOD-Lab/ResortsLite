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
 * <p>Java 17 migration notes:
 * <ul>
 *   <li>Replaced legacy {@code java.util.Date} + {@code SimpleDateFormat} with
 *       {@code java.time.LocalDateTime} + {@code DateTimeFormatter} (thread-safe,
 *       immutable, recommended since Java 8 / JSR-310).
 *       Rule: JAVA8_TO_17_SPECIFIC_DEPENDENCY_UPDATES — deprecated date/time APIs.</li>
 *   <li>Hardcoded absolute paths replaced with configurable properties injected via
 *       {@code @Value}. Rule: czr-java-001 [Software Portability / Mandatory].</li>
 *   <li>Hardcoded server port replaced with configurable property.
 *       Rule: czr-port-001 [Software Portability / Mandatory].</li>
 *   <li>Plain HTTP download URL replaced with configurable property.
 *       Rule: cr-java-0088 [Cloud Compatibility / Mandatory].</li>
 * </ul>
 */
@Service
public class ReportService {

    // Externalised report base path — injected from application properties / environment variable.
    // Rule: czr-java-001 [Software Portability / Mandatory] — /var/legacy/reports does not
    // exist in a Docker container image; path is now configurable via REPORT_BASE_PATH env var.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // Externalised server port — injected from application properties / environment variable.
    // Rule: czr-port-001 [Software Portability / Mandatory] — fixed port replaced with
    // dynamic binding; container orchestration (ECS/EKS) assigns ports at runtime.
    @Value("${server.port:8080}")
    private int serverPort;

    // Externalised report download base URL — injected from application properties.
    // Rule: cr-java-0088 [Cloud Compatibility / Mandatory] — plain HTTP URL replaced with
    // configurable property; HTTPS enforced in production via REPORT_DOWNLOAD_BASE_URL env var.
    @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download}")
    private String reportDownloadBaseUrl;

    /**
     * Generates a monthly CSV report for the given month and year.
     *
     * @param month the month (e.g. "03")
     * @param year  the four-digit year (e.g. "2024")
     * @return a result map containing status, path, and serverPort
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
     * <p>Fixed: plain HTTP URL replaced with configurable base URL injected via
     * {@code @Value}. Rule: cr-java-0088 [Cloud Compatibility / Mandatory] — HTTPS
     * enforced in production by setting APP_REPORT_DOWNLOAD_BASE_URL env var.
     *
     * @param reportName the report file name
     * @return the full download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information including paths, port, and current timestamp.
     *
     * <p>Updated: replaced {@code new SimpleDateFormat(...).format(new Date())} with
     * {@code LocalDateTime.now().format(DateTimeFormatter.ofPattern(...))} — the modern,
     * thread-safe JSR-310 equivalent.
     * Rule: JAVA8_TO_17_SPECIFIC_DEPENDENCY_UPDATES — legacy java.util.Date deprecated.
     *
     * @return a map of system info key/value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Updated from: new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
        // To: LocalDateTime + DateTimeFormatter — thread-safe, immutable, Java 17 idiomatic.
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
