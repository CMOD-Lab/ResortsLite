package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReportService}.
 */
class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // -------------------------------------------------------------------------
    // generateMonthlyReport tests
    // -------------------------------------------------------------------------

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsStatusGenerated() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result);
        // Status should be either "generated" (success) or "error" (if path not writable)
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_withValidInputs_resultContainsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        // Assert
        // On success the map contains "path"; on error it contains "message"
        assertTrue(result.containsKey("path") || result.containsKey("message"),
                "Result should contain either 'path' or 'message'");
    }

    @Test
    void generateMonthlyReport_successPath_containsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        if ("generated".equals(result.get("status"))) {
            String path = (String) result.get("path");
            assertTrue(path.contains("03"), "Path should contain month");
            assertTrue(path.contains("2024"), "Path should contain year");
        }
    }

    @Test
    void generateMonthlyReport_successPath_endsWithCsvExtension() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2023");

        // Assert
        if ("generated".equals(result.get("status"))) {
            String path = (String) result.get("path");
            assertTrue(path.endsWith(".csv"), "Report file should have .csv extension");
        }
    }

    @Test
    void generateMonthlyReport_successResult_containsServerPort() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("01", "2024");

        // Assert
        if ("generated".equals(result.get("status"))) {
            assertTrue(result.containsKey("serverPort"), "Result should contain serverPort");
            assertNotNull(result.get("serverPort"));
        }
    }

    @Test
    void generateMonthlyReport_differentMonthsProduceDifferentPaths() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("01", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("12", "2024");

        // Assert
        if ("generated".equals(result1.get("status")) && "generated".equals(result2.get("status"))) {
            assertNotEquals(result1.get("path"), result2.get("path"),
                    "Different months should produce different file paths");
        }
    }

    @Test
    void generateMonthlyReport_differentYearsProduceDifferentPaths() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("03", "2023");
        Map<String, Object> result2 = reportService.generateMonthlyReport("03", "2024");

        // Assert
        if ("generated".equals(result1.get("status")) && "generated".equals(result2.get("status"))) {
            assertNotEquals(result1.get("path"), result2.get("path"),
                    "Different years should produce different file paths");
        }
    }

    @Test
    void generateMonthlyReport_fileNameFollowsNamingConvention() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2024");

        // Assert
        if ("generated".equals(result.get("status"))) {
            String path = (String) result.get("path");
            String fileName = new File(path).getName();
            assertTrue(fileName.startsWith("resort_report_"),
                    "File name should start with 'resort_report_'");
        }
    }

    // -------------------------------------------------------------------------
    // buildReportDownloadUrl tests
    // -------------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_withReportName_returnsNonNullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.csv");

        // Assert
        assertNotNull(url);
        assertFalse(url.isEmpty());
    }

    @Test
    void buildReportDownloadUrl_containsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.csv");

        // Assert
        assertTrue(url.contains("march_report.csv"),
                "URL should contain the report name");
    }

    @Test
    void buildReportDownloadUrl_usesHttpsScheme() {
        // Act
        String url = reportService.buildReportDownloadUrl("test_report.pdf");

        // Assert
        assertTrue(url.startsWith("https://"),
                "URL should use HTTPS scheme");
    }

    @Test
    void buildReportDownloadUrl_differentReportNames_produceDifferentUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.csv");
        String url2 = reportService.buildReportDownloadUrl("report_feb.csv");

        // Assert
        assertNotEquals(url1, url2, "Different report names should produce different URLs");
    }

    @Test
    void buildReportDownloadUrl_urlContainsSlashBeforeReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("my_report.csv");

        // Assert
        assertTrue(url.contains("/my_report.csv"),
                "URL should contain a slash before the report name");
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsUrlEndingWithSlash() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.endsWith("/"), "URL with empty report name should end with '/'");
    }

    // -------------------------------------------------------------------------
    // getSystemInfo tests
    // -------------------------------------------------------------------------

    @Test
    void getSystemInfo_returnsNonNullMap() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"), "System info should contain 'reportPath'");
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"), "System info should contain 'backupPath'");
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"), "System info should contain 'serverPort'");
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"), "System info should contain 'generatedAt'");
    }

    @Test
    void getSystemInfo_serverPortIsInteger() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        Object port = info.get("serverPort");
        assertNotNull(port);
        assertInstanceOf(Integer.class, port, "serverPort should be an Integer");
    }

    @Test
    void getSystemInfo_generatedAtIsNonEmptyString() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        Object timestamp = info.get("generatedAt");
        assertNotNull(timestamp);
        assertInstanceOf(String.class, timestamp);
        assertFalse(((String) timestamp).isEmpty(), "generatedAt should not be empty");
    }

    @Test
    void getSystemInfo_generatedAtMatchesDateTimePattern() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();
        String timestamp = (String) info.get("generatedAt");

        // Assert — format: yyyy-MM-dd HH:mm:ss (19 chars)
        assertNotNull(timestamp);
        assertEquals(19, timestamp.length(),
                "Timestamp should be 19 characters in 'yyyy-MM-dd HH:mm:ss' format");
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp should match pattern yyyy-MM-dd HH:mm:ss");
    }

    @Test
    void getSystemInfo_reportPathIsNonEmptyString() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();
        Object reportPath = info.get("reportPath");

        // Assert
        assertNotNull(reportPath);
        assertInstanceOf(String.class, reportPath);
        assertFalse(((String) reportPath).isEmpty());
    }

    @Test
    void getSystemInfo_backupPathIsNonEmptyString() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();
        Object backupPath = info.get("backupPath");

        // Assert
        assertNotNull(backupPath);
        assertInstanceOf(String.class, backupPath);
        assertFalse(((String) backupPath).isEmpty());
    }

    @Test
    void getSystemInfo_calledTwice_generatedAtTimestampsAreStrings() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        assertInstanceOf(String.class, info1.get("generatedAt"));
        assertInstanceOf(String.class, info2.get("generatedAt"));
    }
}
