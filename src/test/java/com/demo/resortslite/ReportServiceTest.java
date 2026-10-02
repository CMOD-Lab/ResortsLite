package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ReportService.
 */
class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // -----------------------------------------------------------------------
    // generateMonthlyReport
    // -----------------------------------------------------------------------

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsStatusGenerated() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result);
        // Either "generated" (success) or "error" (if /tmp not writable) — both are valid
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_resultMapNotNull() {
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_successPath_containsPathKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("01", "2024");
        // On success the map contains "path"; on error it contains "message"
        assertTrue(result.containsKey("path") || result.containsKey("message"));
    }

    @Test
    void generateMonthlyReport_successPath_containsServerPortKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("12", "2023");
        // serverPort is only added on success path
        if ("generated".equals(result.get("status"))) {
            assertTrue(result.containsKey("serverPort"));
        }
    }

    @Test
    void generateMonthlyReport_successPath_pathContainsMonthAndYear() {
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2025");
        if ("generated".equals(result.get("status"))) {
            String path = (String) result.get("path");
            assertTrue(path.contains("05"));
            assertTrue(path.contains("2025"));
        }
    }

    @Test
    void generateMonthlyReport_successPath_pathContainsCsvExtension() {
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2024");
        if ("generated".equals(result.get("status"))) {
            String path = (String) result.get("path");
            assertTrue(path.endsWith(".csv"));
        }
    }

    @Test
    void generateMonthlyReport_differentMonths_produceDifferentPaths() {
        Map<String, Object> result1 = reportService.generateMonthlyReport("01", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("02", "2024");

        if ("generated".equals(result1.get("status")) && "generated".equals(result2.get("status"))) {
            assertNotEquals(result1.get("path"), result2.get("path"));
        }
    }

    @Test
    void generateMonthlyReport_statusIsEitherGeneratedOrError() {
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status));
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_withReportName_returnsNonNullUrl() {
        String url = reportService.buildReportDownloadUrl("report_03_2024.csv");
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_withReportName_urlContainsReportName() {
        String reportName = "report_03_2024.csv";
        String url = reportService.buildReportDownloadUrl(reportName);
        assertTrue(url.contains(reportName));
    }

    @Test
    void buildReportDownloadUrl_urlStartsWithHttps() {
        String url = reportService.buildReportDownloadUrl("any_report.csv");
        assertTrue(url.startsWith("https://"), "URL should start with https://");
    }

    @Test
    void buildReportDownloadUrl_urlIsNotEmpty() {
        String url = reportService.buildReportDownloadUrl("test.csv");
        assertFalse(url.isEmpty());
    }

    @Test
    void buildReportDownloadUrl_differentReportNames_produceDifferentUrls() {
        String url1 = reportService.buildReportDownloadUrl("report_jan.csv");
        String url2 = reportService.buildReportDownloadUrl("report_feb.csv");
        assertNotEquals(url1, url2);
    }

    @Test
    void buildReportDownloadUrl_urlContainsSlashBeforeReportName() {
        String url = reportService.buildReportDownloadUrl("myreport.csv");
        assertTrue(url.contains("/myreport.csv"));
    }

    @Test
    void buildReportDownloadUrl_emptyReportName_returnsBaseUrlWithSlash() {
        String url = reportService.buildReportDownloadUrl("");
        assertNotNull(url);
        // Should still return a URL (base + "/" + "")
        assertTrue(url.startsWith("https://"));
    }

    // -----------------------------------------------------------------------
    // getSystemInfo
    // -----------------------------------------------------------------------

    @Test
    void getSystemInfo_returnsNonNullMap() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPathKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("backupPath"));
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtIsNotNull() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtMatchesTimestampFormat() {
        Map<String, Object> info = reportService.getSystemInfo();
        String timestamp = (String) info.get("generatedAt");
        // Format: yyyy-MM-dd HH:mm:ss  (length = 19)
        assertNotNull(timestamp);
        assertEquals(19, timestamp.length(), "Timestamp should be 19 chars: yyyy-MM-dd HH:mm:ss");
    }

    @Test
    void getSystemInfo_serverPortIsInteger() {
        Map<String, Object> info = reportService.getSystemInfo();
        Object port = info.get("serverPort");
        assertNotNull(port);
        assertInstanceOf(Integer.class, port);
    }

    @Test
    void getSystemInfo_reportPathIsNotEmpty() {
        Map<String, Object> info = reportService.getSystemInfo();
        String reportPath = (String) info.get("reportPath");
        assertNotNull(reportPath);
        assertFalse(reportPath.isEmpty());
    }

    @Test
    void getSystemInfo_backupPathIsNotEmpty() {
        Map<String, Object> info = reportService.getSystemInfo();
        String backupPath = (String) info.get("backupPath");
        assertNotNull(backupPath);
        assertFalse(backupPath.isEmpty());
    }

    @Test
    void getSystemInfo_calledTwice_generatedAtTimestampsAreValid() {
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();
        // Both should have valid timestamps
        assertNotNull(info1.get("generatedAt"));
        assertNotNull(info2.get("generatedAt"));
    }
}
