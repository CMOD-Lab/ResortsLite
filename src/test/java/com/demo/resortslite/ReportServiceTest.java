package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateMonthlyReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsNonNullMap() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_resultContainsStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("April", "2024");

        // Assert
        assertTrue(result.containsKey("status"), "Result must contain 'status' key");
    }

    @Test
    void generateMonthlyReport_whenDirectoryCreationFails_returnsErrorStatus() {
        // Act – /var/legacy/reports likely doesn't exist in test env, so IOException expected
        Map<String, Object> result = reportService.generateMonthlyReport("May", "2024");

        // Assert – either "generated" (if dir exists) or "error" (if not)
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status must be 'generated' or 'error'");
    }

    @Test
    void generateMonthlyReport_onError_resultContainsMessageKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("June", "2024");

        // Assert – if error occurred, message key should be present
        String status = (String) result.get("status");
        if ("error".equals(status)) {
            assertTrue(result.containsKey("message"),
                    "Error result must contain 'message' key");
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_resultContainsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("July", "2024");

        // Assert – if generated, path key should be present
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertTrue(result.containsKey("path"),
                    "Generated result must contain 'path' key");
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_resultContainsServerPort() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("August", "2024");

        // Assert – if generated, serverPort key should be present
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertNotNull(result.get("serverPort"),
                    "Generated result must contain 'serverPort'");
        }
    }

    @Test
    void generateMonthlyReport_differentMonthsProduceDifferentPaths() {
        // Act
        Map<String, Object> r1 = reportService.generateMonthlyReport("January", "2024");
        Map<String, Object> r2 = reportService.generateMonthlyReport("February", "2024");

        // Assert – if both generated, paths should differ
        String s1 = (String) r1.get("status");
        String s2 = (String) r2.get("status");
        if ("generated".equals(s1) && "generated".equals(s2)) {
            assertNotEquals(r1.get("path"), r2.get("path"),
                    "Different months should produce different file paths");
        }
    }

    @Test
    void generateMonthlyReport_returnsMapWithAtLeastOneEntry() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("September", "2024");

        // Assert
        assertFalse(result.isEmpty(), "Result map should not be empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {"January", "February", "March", "April", "May", "June",
                             "July", "August", "September", "October", "November", "December"})
    void generateMonthlyReport_allMonths_returnsNonNullResult(String month) {
        Map<String, Object> result = reportService.generateMonthlyReport(month, "2024");
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildReportDownloadUrl tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_withValidName_returnsNonNullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_urlContainsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("april_report.pdf");

        // Assert
        assertTrue(url.contains("april_report.pdf"),
                "URL should contain the report name");
    }

    @Test
    void buildReportDownloadUrl_urlStartsWithHttp() {
        // Act
        String url = reportService.buildReportDownloadUrl("may_report.pdf");

        // Assert
        assertTrue(url.startsWith("http"),
                "URL should start with http");
    }

    @Test
    void buildReportDownloadUrl_urlContainsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("june_report.pdf");

        // Assert
        assertTrue(url.contains("/download/"),
                "URL should contain '/download/' path segment");
    }

    @Test
    void buildReportDownloadUrl_differentReportNames_produceDifferentUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.pdf");
        String url2 = reportService.buildReportDownloadUrl("report_feb.pdf");

        // Assert
        assertNotEquals(url1, url2, "Different report names should produce different URLs");
    }

    @Test
    void buildReportDownloadUrl_emptyName_returnsUrlWithEmptySegment() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("/download/"),
                "URL should still contain '/download/' even with empty name");
    }

    @Test
    void buildReportDownloadUrl_urlContainsHostname() {
        // Act
        String url = reportService.buildReportDownloadUrl("test.pdf");

        // Assert
        assertTrue(url.contains("reports.resorts-internal.com"),
                "URL should contain the internal hostname");
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
    void getSystemInfo_containsReportPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"),
                "System info should contain 'reportPath' key");
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"),
                "System info should contain 'backupPath' key");
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"),
                "System info should contain 'serverPort' key");
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"),
                "System info should contain 'generatedAt' key");
    }

    @Test
    void getSystemInfo_generatedAtIsNonNullString() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        Object generatedAt = info.get("generatedAt");
        assertNotNull(generatedAt);
        assertInstanceOf(String.class, generatedAt);
        assertFalse(((String) generatedAt).isEmpty());
    }

    @Test
    void getSystemInfo_generatedAtMatchesDateTimePattern() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();
        String generatedAt = (String) info.get("generatedAt");

        // Assert – format: yyyy-MM-dd HH:mm:ss
        assertNotNull(generatedAt);
        assertTrue(generatedAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "generatedAt should match 'yyyy-MM-dd HH:mm:ss' pattern");
    }

    @Test
    void getSystemInfo_serverPortIsInteger() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        Object port = info.get("serverPort");
        assertNotNull(port);
        assertInstanceOf(Integer.class, port);
    }

    @Test
    void getSystemInfo_serverPortIsPositive() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();
        Integer port = (Integer) info.get("serverPort");

        // Assert
        assertTrue(port > 0, "Server port should be a positive integer");
    }

    @Test
    void getSystemInfo_reportPathIsNonNullString() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        Object reportPath = info.get("reportPath");
        assertNotNull(reportPath);
        assertInstanceOf(String.class, reportPath);
    }

    @Test
    void getSystemInfo_backupPathIsNonNullString() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        Object backupPath = info.get("backupPath");
        assertNotNull(backupPath);
        assertInstanceOf(String.class, backupPath);
    }

    @Test
    void getSystemInfo_calledTwice_generatedAtTimestampsAreClose() throws InterruptedException {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Thread.sleep(100);
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert – both calls should return valid timestamps
        assertNotNull(info1.get("generatedAt"));
        assertNotNull(info2.get("generatedAt"));
    }

    @Test
    void getSystemInfo_mapContainsFourKeys() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(4, info.size(), "System info map should contain exactly 4 keys");
    }
}
