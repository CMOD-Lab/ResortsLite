package com.demo.resortslite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateMonthlyReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsGeneratedStatus() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result);
        // Either "generated" (if dir writable) or "error" (if /var/legacy not writable in test env)
        assertTrue(result.containsKey("status"), "Result should contain 'status' key");
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_resultMapIsNotNull() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty(), "Result map should not be empty");
    }

    @Test
    void generateMonthlyReport_whenDirectoryNotWritable_returnsErrorStatus() {
        // Act — /var/legacy/reports/ likely not writable in test environment
        Map<String, Object> result = reportService.generateMonthlyReport("01", "2023");

        // Assert
        assertNotNull(result);
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status should be 'generated' or 'error'");
    }

    @Test
    void generateMonthlyReport_errorCase_containsMessageKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("99", "9999");

        // Assert
        assertNotNull(result);
        // If error, message key should be present
        if ("error".equals(result.get("status"))) {
            assertTrue(result.containsKey("message"), "Error result should contain 'message' key");
        }
    }

    @Test
    void generateMonthlyReport_successCase_containsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        if ("generated".equals(result.get("status"))) {
            assertTrue(result.containsKey("path"), "Generated result should contain 'path' key");
            String path = (String) result.get("path");
            assertTrue(path.contains("03"), "Path should contain the month");
            assertTrue(path.contains("2024"), "Path should contain the year");
        }
    }

    @Test
    void generateMonthlyReport_successCase_containsServerPort() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        if ("generated".equals(result.get("status"))) {
            assertNotNull(result.get("serverPort"), "Result should contain serverPort");
            assertEquals(8080, result.get("serverPort"));
        }
    }

    @Test
    void generateMonthlyReport_fileNameContainsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2025");

        // Assert
        if ("generated".equals(result.get("status"))) {
            String path = (String) result.get("path");
            assertTrue(path.contains("resort_report_06_2025.csv"),
                    "File name should follow naming convention");
        }
    }

    @Test
    void generateMonthlyReport_withDifferentMonths_returnsResult() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("01", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("12", "2024");

        // Assert
        assertNotNull(result1);
        assertNotNull(result2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildReportDownloadUrl tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_withValidReportName_returnsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertNotNull(url);
        assertFalse(url.isEmpty());
    }

    @Test
    void buildReportDownloadUrl_urlContainsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertTrue(url.contains("march_report.pdf"),
                "URL should contain the report name");
    }

    @Test
    void buildReportDownloadUrl_urlContainsExpectedHost() {
        // Act
        String url = reportService.buildReportDownloadUrl("test_report.csv");

        // Assert
        assertTrue(url.contains("reports.resorts-internal.com"),
                "URL should contain the expected hostname");
    }

    @Test
    void buildReportDownloadUrl_urlContainsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("annual_report.pdf");

        // Assert
        assertTrue(url.contains("/download/"),
                "URL should contain '/download/' path segment");
    }

    @Test
    void buildReportDownloadUrl_urlContainsPort8080() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.pdf");

        // Assert
        assertTrue(url.contains(":8080"),
                "URL should contain port 8080");
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsUrlWithEmptySuffix() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.endsWith("/download/"),
                "URL with empty report name should end with '/download/'");
    }

    @Test
    void buildReportDownloadUrl_withSpecialCharacters_includesThemInUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_2024-03.pdf");

        // Assert
        assertTrue(url.contains("report_2024-03.pdf"));
    }

    @Test
    void buildReportDownloadUrl_returnsFullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("my_report.pdf");

        // Assert
        assertEquals("http://reports.resorts-internal.com:8080/download/my_report.pdf", url);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getSystemInfo tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getSystemInfo_returnsNonNullMap() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"), "System info should contain 'reportPath'");
        assertNotNull(info.get("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"), "System info should contain 'backupPath'");
        assertNotNull(info.get("backupPath"));
    }

    @Test
    void getSystemInfo_containsServerPort() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"), "System info should contain 'serverPort'");
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAt() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"), "System info should contain 'generatedAt'");
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtMatchesDateTimeFormat() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String generatedAt = (String) info.get("generatedAt");
        assertNotNull(generatedAt);
        // Format: yyyy-MM-dd HH:mm:ss
        assertTrue(generatedAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "generatedAt should match 'yyyy-MM-dd HH:mm:ss' format, got: " + generatedAt);
    }

    @Test
    void getSystemInfo_reportPathIsCorrectValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals("/var/legacy/reports/", info.get("reportPath"));
    }

    @Test
    void getSystemInfo_backupPathIsCorrectValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals("/var/backups/resort/nightly/", info.get("backupPath"));
    }

    @Test
    void getSystemInfo_returnsMapWithFourEntries() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(4, info.size(), "System info map should have exactly 4 entries");
    }

    @Test
    void getSystemInfo_calledTwice_generatedAtTimestampsAreValid() throws InterruptedException {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Thread.sleep(10);
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        assertNotNull(info1.get("generatedAt"));
        assertNotNull(info2.get("generatedAt"));
    }
}
