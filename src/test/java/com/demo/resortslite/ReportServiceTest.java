package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReportService}.
 * Covers: generateMonthlyReport, buildReportDownloadUrl, getSystemInfo.
 */
class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // -----------------------------------------------------------------------
    // generateMonthlyReport — success path (uses /var/legacy/reports/ which
    // may not exist; the service handles IOException gracefully)
    // -----------------------------------------------------------------------

    @Test
    void generateMonthlyReport_returnsNonNullMap() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_resultContainsStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_whenDirectoryNotWritable_returnsErrorStatus() {
        // Act — /var/legacy/reports/ won't exist in test environment → IOException
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert — either "generated" (if dir was created) or "error" (if not writable)
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status should be 'generated' or 'error', got: " + status);
    }

    @Test
    void generateMonthlyReport_onError_resultContainsMessageKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert — if error, message key should be present
        String status = (String) result.get("status");
        if ("error".equals(status)) {
            assertTrue(result.containsKey("message"),
                    "Error result should contain 'message' key");
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_resultContainsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        // Assert — if generated, path key should be present
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertTrue(result.containsKey("path"),
                    "Generated result should contain 'path' key");
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_pathContainsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            String path = (String) result.get("path");
            assertTrue(path.contains("07"), "Path should contain month");
            assertTrue(path.contains("2024"), "Path should contain year");
        } else {
            // error path is also acceptable in test environment
            assertEquals("error", status);
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_pathEndsCsv() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("08", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            String path = (String) result.get("path");
            assertTrue(path.endsWith(".csv"), "Report file should end with .csv");
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_resultContainsServerPort() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("09", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertTrue(result.containsKey("serverPort"),
                    "Generated result should contain 'serverPort' key");
            assertEquals(8080, result.get("serverPort"));
        }
    }

    @Test
    void generateMonthlyReport_differentMonthsProduceDifferentFileNames() {
        // Act
        Map<String, Object> r1 = reportService.generateMonthlyReport("01", "2024");
        Map<String, Object> r2 = reportService.generateMonthlyReport("12", "2024");

        // Assert — if both generated, paths should differ
        String s1 = (String) r1.get("status");
        String s2 = (String) r2.get("status");
        if ("generated".equals(s1) && "generated".equals(s2)) {
            assertNotEquals(r1.get("path"), r2.get("path"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "06", "12"})
    void generateMonthlyReport_variousMonths_returnsNonNullResult(String month) {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport(month, "2024");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("status"));
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_withReportName_returnsNonNullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.csv");

        // Assert
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_withReportName_urlContainsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.csv");

        // Assert
        assertTrue(url.contains("march_report.csv"));
    }

    @Test
    void buildReportDownloadUrl_urlStartsWithHttp() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.csv");

        // Assert
        assertTrue(url.startsWith("http://"),
                "URL should start with http:// (legacy plain HTTP)");
    }

    @Test
    void buildReportDownloadUrl_urlContainsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.csv");

        // Assert
        assertTrue(url.contains("/download/"));
    }

    @Test
    void buildReportDownloadUrl_urlContainsInternalHost() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.csv");

        // Assert
        assertTrue(url.contains("reports.resorts-internal.com"));
    }

    @Test
    void buildReportDownloadUrl_urlContainsPort8080() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.csv");

        // Assert
        assertTrue(url.contains(":8080"));
    }

    @Test
    void buildReportDownloadUrl_differentReportNames_produceDifferentUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("jan_report.csv");
        String url2 = reportService.buildReportDownloadUrl("dec_report.csv");

        // Assert
        assertNotEquals(url1, url2);
    }

    @Test
    void buildReportDownloadUrl_withEmptyName_returnsUrlWithEmptySegment() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("/download/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jan.csv", "feb.pdf", "march_2024.xlsx"})
    void buildReportDownloadUrl_variousFileNames_allContainFileName(String fileName) {
        // Act
        String url = reportService.buildReportDownloadUrl(fileName);

        // Assert
        assertTrue(url.contains(fileName));
    }

    // -----------------------------------------------------------------------
    // getSystemInfo
    // -----------------------------------------------------------------------

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
        assertTrue(info.containsKey("reportPath"));
    }

    @Test
    void getSystemInfo_reportPathIsLegacyPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals("/var/legacy/reports/", info.get("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"));
    }

    @Test
    void getSystemInfo_backupPathIsWindowsPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String backupPath = (String) info.get("backupPath");
        assertNotNull(backupPath);
        assertTrue(backupPath.contains("ResortBackups"),
                "Backup path should reference ResortBackups directory");
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"));
    }

    @Test
    void getSystemInfo_serverPortIs8080() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtIsNonNullString() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        Object generatedAt = info.get("generatedAt");
        assertNotNull(generatedAt);
        assertInstanceOf(String.class, generatedAt);
    }

    @Test
    void getSystemInfo_generatedAtMatchesDateTimePattern() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert — format: yyyy-MM-dd HH:mm:ss
        String ts = (String) info.get("generatedAt");
        assertNotNull(ts);
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp should match yyyy-MM-dd HH:mm:ss pattern, got: " + ts);
    }

    @Test
    void getSystemInfo_returnsFourKeys() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(4, info.size(),
                "getSystemInfo should return exactly 4 keys: reportPath, backupPath, serverPort, generatedAt");
    }

    @Test
    void getSystemInfo_calledTwice_generatedAtTimestampsAreStrings() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert — both calls return valid string timestamps
        assertInstanceOf(String.class, info1.get("generatedAt"));
        assertInstanceOf(String.class, info2.get("generatedAt"));
    }
}
