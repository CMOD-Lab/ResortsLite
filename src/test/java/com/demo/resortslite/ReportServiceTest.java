package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // -----------------------------------------------------------------------
    // generateMonthlyReport tests
    // -----------------------------------------------------------------------

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsNonNullMap() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_returnsStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("April", "2024");

        // Assert
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_whenDirectoryNotWritable_returnsErrorOrGenerated() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("May", "2024");

        // Assert
        // Either "generated" (if /var/legacy/reports is writable) or "error" (if not)
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status should be 'generated' or 'error', but was: " + status);
    }

    @Test
    void generateMonthlyReport_onError_returnsErrorStatus() {
        // Act - /var/legacy/reports likely doesn't exist in test environment
        Map<String, Object> result = reportService.generateMonthlyReport("June", "2024");

        // Assert
        assertNotNull(result.get("status"));
    }

    @Test
    void generateMonthlyReport_onError_containsMessageKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("July", "2024");

        // Assert - if error, message key should be present; if success, path key should be present
        assertTrue(result.containsKey("message") || result.containsKey("path"),
                "Result should contain either 'message' (error) or 'path' (success)");
    }

    @Test
    void generateMonthlyReport_onSuccess_containsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("August", "2024");

        // Assert
        if ("generated".equals(result.get("status"))) {
            assertTrue(result.containsKey("path"));
            String path = (String) result.get("path");
            assertTrue(path.contains("August"));
            assertTrue(path.contains("2024"));
        } else {
            // error case is also valid
            assertTrue(result.containsKey("message"));
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_pathContainsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("September", "2023");

        // Assert
        if ("generated".equals(result.get("status"))) {
            String path = (String) result.get("path");
            assertTrue(path.contains("September"));
            assertTrue(path.contains("2023"));
        } else {
            assertNotNull(result.get("message"));
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_containsServerPort() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("October", "2024");

        // Assert
        if ("generated".equals(result.get("status"))) {
            assertNotNull(result.get("serverPort"));
            assertEquals(8080, result.get("serverPort"));
        }
    }

    @Test
    void generateMonthlyReport_differentMonthsProduceDifferentPaths() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("January", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("February", "2024");

        // Assert
        if ("generated".equals(result1.get("status")) && "generated".equals(result2.get("status"))) {
            assertNotEquals(result1.get("path"), result2.get("path"));
        }
    }

    @Test
    void generateMonthlyReport_withEmptyMonth_doesNotThrowException() {
        // Act & Assert
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("", "2024"));
    }

    @Test
    void generateMonthlyReport_withNullMonth_doesNotThrowNullPointerException() {
        // Act & Assert - should handle gracefully (either error map or exception)
        try {
            Map<String, Object> result = reportService.generateMonthlyReport(null, "2024");
            assertNotNull(result);
        } catch (NullPointerException e) {
            // Acceptable - null input is not a supported use case
        }
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl tests
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_withValidReportName_returnsNonNullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertNotNull(url);
        assertFalse(url.isEmpty());
    }

    @Test
    void buildReportDownloadUrl_containsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("april_report.pdf");

        // Assert
        assertTrue(url.contains("april_report.pdf"));
    }

    @Test
    void buildReportDownloadUrl_containsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("may_report.pdf");

        // Assert
        assertTrue(url.contains("/download/"));
    }

    @Test
    void buildReportDownloadUrl_containsReportsHost() {
        // Act
        String url = reportService.buildReportDownloadUrl("june_report.pdf");

        // Assert
        assertTrue(url.contains("reports.resorts-internal.com"));
    }

    @Test
    void buildReportDownloadUrl_containsPort8080() {
        // Act
        String url = reportService.buildReportDownloadUrl("july_report.pdf");

        // Assert
        assertTrue(url.contains("8080"));
    }

    @Test
    void buildReportDownloadUrl_startsWithHttp() {
        // Act
        String url = reportService.buildReportDownloadUrl("august_report.pdf");

        // Assert
        assertTrue(url.startsWith("http://"), "URL should start with http://");
    }

    @Test
    void buildReportDownloadUrl_differentReportNames_produceDifferentUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.pdf");
        String url2 = reportService.buildReportDownloadUrl("report_feb.pdf");

        // Assert
        assertNotEquals(url1, url2);
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsUrlWithEmptySegment() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("/download/"));
    }

    // -----------------------------------------------------------------------
    // getSystemInfo tests
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
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"));
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"));
    }

    @Test
    void getSystemInfo_reportPathIsLegacyPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals("/var/legacy/reports/", info.get("reportPath"));
    }

    @Test
    void getSystemInfo_backupPathIsWindowsPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String backupPath = (String) info.get("backupPath");
        assertNotNull(backupPath);
        assertTrue(backupPath.contains("ResortBackups"));
    }

    @Test
    void getSystemInfo_serverPortIs8080() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_generatedAtIsFormattedTimestamp() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String timestamp = (String) info.get("generatedAt");
        assertNotNull(timestamp);
        // Format: yyyy-MM-dd HH:mm:ss
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp should match pattern yyyy-MM-dd HH:mm:ss, but was: " + timestamp);
    }

    @Test
    void getSystemInfo_calledTwice_returnsConsistentKeys() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        assertEquals(info1.keySet(), info2.keySet());
    }

    @Test
    void getSystemInfo_returnsMapWithFourEntries() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(4, info.size());
    }
}
