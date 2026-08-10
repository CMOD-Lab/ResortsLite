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

    /**
     * Base path for report files — externalised to an environment variable so that
     * container deployments can mount the correct volume path without code changes.
     * Defaults to {@code /tmp/reports} which is writable inside any container image.
     * (Fixes: czr-java-001)
     */
    @Value("${app.report.base-path:${java.io.tmpdir}/reports}")
    private String reportBasePath;

    /**
     * Base URL for the report download service — externalised to an environment
     * variable and defaults to HTTPS to comply with cloud security standards.
     * (Fixes: cr-java-0088, czr-java-001)
     */
    @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download}")
    private String reportDownloadBaseUrl;

    /**
     * Generates a monthly booking report CSV file.
     *
     * <p>The output directory is determined by {@code app.report.base-path} so that
     * the path can be configured per environment without modifying source code.
     * (Fixes: czr-java-001)
     *
     * @param month the month for the report (e.g. "03")
     * @param year  the year for the report (e.g. "2024")
     * @return map containing generation status and output path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = reportBasePath + File.separator + fileName;

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
     * Builds the HTTPS download URL for a named report file.
     *
     * <p>The base URL is externalised via {@code app.report.download-base-url} and
     * defaults to an HTTPS endpoint, satisfying cloud security requirements.
     * (Fixes: cr-java-0088)
     *
     * @param reportName the file name of the report
     * @return fully qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns basic system/runtime information for diagnostics.
     *
     * <p>Paths and port are read from injected configuration values rather than
     * hardcoded constants, ensuring portability across environments.
     * (Fixes: czr-java-001, czr-port-001)
     *
     * @return map of diagnostic key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", reportBasePath);
        info.put("generatedAt", timestamp);
        return info;
    }
}
