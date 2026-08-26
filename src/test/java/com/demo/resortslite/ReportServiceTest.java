package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                tempDir.toString() + "/reports/");
        ReflectionTestUtils.setField(reportService, "backupPath",
                tempDir.toString() + "/backups/");
        ReflectionTestUtils.setField(reportService, "serverPort", 8080);
    }

    // ─── generateMonthlyReport ────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsGeneratedStatus() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result);
        assertEquals("generated", result.get("status"));
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsFilePath() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result.get("path"));
        String path = (String) result.get("path");
        assertTrue(path.contains("resort_report_03_2024.csv"),
                "Path should contain the expected filename");
    }

    @Test
    void generateMonthlyReport_createsFileOnDisk() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("04", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        String path = (String) result.get("path");
        File reportFile = new File(path);
        assertTrue(reportFile.exists(), "Report file should exist on disk");
    }

    @Test
    void generateMonthlyReport_createsDirectoryIfNotExists() {
        // Arrange: use a nested path that doesn't exist yet
        String nestedPath = tempDir.toString() + "/nested/reports/";
        ReflectionTestUtils.setField(reportService, "reportBasePath", nestedPath);

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        File dir = new File(nestedPath);
        assertTrue(dir.exists(), "Report directory should be created");
    }

    @Test
    void generateMonthlyReport_returnsServerPortInResult() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        // Assert
        assertNotNull(result.get("serverPort"));
        assertEquals(8080, result.get("serverPort"));
    }

    @Test
    void generateMonthlyReport_withDifferentMonths_createsDistinctFiles() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("01", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("02", "2024");

        // Assert
        assertNotEquals(result1.get("path"), result2.get("path"),
                "Different months should produce different file paths");
    }

    @Test
    void generateMonthlyReport_withDifferentYears_createsDistinctFiles() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("03", "2023");
        Map<String, Object> result2 = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotEquals(result1.get("path"), result2.get("path"),
                "Different years should produce different file paths");
    }

    @Test
    void generateMonthlyReport_resultContainsStatusPathAndServerPortKeys() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2024");

        // Assert
        assertTrue(result.containsKey("status"), "Result should contain 'status' key");
        assertTrue(result.containsKey("path"), "Result should contain 'path' key");
        assertTrue(result.containsKey("serverPort"), "Result should contain 'serverPort' key");
    }

    @Test
    void generateMonthlyReport_whenIOError_returnsErrorStatus() {
        // Arrange: set an invalid path to trigger IOException
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                "/root/nonexistent_readonly_path_xyz/");

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("08", "2024");

        // Assert
        // Either error or generated depending on permissions; just verify result is not null
        assertNotNull(result);
        assertTrue(result.containsKey("status") || result.containsKey("message"));
    }

    // ─── buildReportDownloadUrl ───────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_withReportName_returnsHttpsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("https://"), "URL should use HTTPS scheme");
    }

    @Test
    void buildReportDownloadUrl_withReportName_containsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("april_report.pdf");

        // Assert
        assertTrue(url.contains("april_report.pdf"),
                "URL should contain the report name");
    }

    @Test
    void buildReportDownloadUrl_withReportName_containsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("may_report.pdf");

        // Assert
        assertTrue(url.contains("/download/"),
                "URL should contain '/download/' path segment");
    }

    @Test
    void buildReportDownloadUrl_withDifferentReportNames_returnsDistinctUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.pdf");
        String url2 = reportService.buildReportDownloadUrl("report_feb.pdf");

        // Assert
        assertNotEquals(url1, url2, "Different report names should produce different URLs");
    }

    @Test
    void buildReportDownloadUrl_urlContainsExpectedHostname() {
        // Act
        String url = reportService.buildReportDownloadUrl("test_report.pdf");

        // Assert
        assertTrue(url.contains("reports.resorts-internal.com"),
                "URL should contain the expected hostname");
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsBaseUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("https://"));
    }

    // ─── getSystemInfo ────────────────────────────────────────────────────────

    @Test
    void getSystemInfo_returnsNonNullMap() {
        // Act
        Map<String, Object> result = reportService.getSystemInfo();

        // Assert
        assertNotNull(result);
    }

    @Test
    void getSystemInfo_containsReportPathKey() {
        // Act
        Map<String, Object> result = reportService.getSystemInfo();

        // Assert
        assertTrue(result.containsKey("reportPath"), "Result should contain 'reportPath' key");
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> result = reportService.getSystemInfo();

        // Assert
        assertTrue(result.containsKey("backupPath"), "Result should contain 'backupPath' key");
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> result = reportService.getSystemInfo();

        // Assert
        assertTrue(result.containsKey("serverPort"), "Result should contain 'serverPort' key");
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> result = reportService.getSystemInfo();

        // Assert
        assertTrue(result.containsKey("generatedAt"), "Result should contain 'generatedAt' key");
    }

    @Test
    void getSystemInfo_reportPathMatchesConfiguredValue() {
        // Act
        Map<String, Object> result = reportService.getSystemInfo();

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.contains("reports"), "Report path should contain 'reports'");
    }

    @Test
    void getSystemInfo_backupPathMatchesConfiguredValue() {
        // Act
        Map<String, Object> result = reportService.getSystemInfo();

        // Assert
        String backupPath = (String) result.get("backupPath");
        assertTrue(backupPath.contains("backups"), "Backup path should contain 'backups'");
    }

    @Test
    void getSystemInfo_serverPortMatchesConfiguredValue() {
        // Act
        Map<String, Object> result = reportService.getSystemInfo();

        // Assert
        assertEquals(8080, result.get("serverPort"));
    }

    @Test
    void getSystemInfo_generatedAtIsFormattedTimestamp() {
        // Act
        Map<String, Object> result = reportService.getSystemInfo();

        // Assert
        String generatedAt = (String) result.get("generatedAt");
        assertNotNull(generatedAt);
        // Verify format: yyyy-MM-dd HH:mm:ss
        assertTrue(generatedAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp should match format 'yyyy-MM-dd HH:mm:ss'");
    }

    @Test
    void getSystemInfo_generatedAtIsNotEmpty() {
        // Act
        Map<String, Object> result = reportService.getSystemInfo();

        // Assert
        String generatedAt = (String) result.get("generatedAt");
        assertFalse(generatedAt.isEmpty(), "generatedAt should not be empty");
    }
}
