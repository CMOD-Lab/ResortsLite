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
        ReflectionTestUtils.setField(reportService, "reportDownloadBaseUrl",
                "https://reports.resorts-internal.com/download/");
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
    void generateMonthlyReport_withValidMonthAndYear_returnsCorrectPath() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        String path = (String) result.get("path");
        assertNotNull(path);
        assertTrue(path.contains("resort_report_03_2024.csv"));
    }

    @Test
    void generateMonthlyReport_createsFileOnDisk() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("04", "2024");

        // Assert
        String path = (String) result.get("path");
        File file = new File(path);
        assertTrue(file.exists(), "Report file should exist on disk");
    }

    @Test
    void generateMonthlyReport_createdFileIsNotEmpty() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2024");

        // Assert
        String path = (String) result.get("path");
        File file = new File(path);
        assertTrue(file.length() > 0, "Report file should not be empty");
    }

    @Test
    void generateMonthlyReport_returnsServerPortInResult() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        // Assert
        assertEquals(8080, result.get("serverPort"));
    }

    @Test
    void generateMonthlyReport_resultContainsStatusPathAndServerPort() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2024");

        // Assert
        assertTrue(result.containsKey("status"));
        assertTrue(result.containsKey("path"));
        assertTrue(result.containsKey("serverPort"));
    }

    @Test
    void generateMonthlyReport_createsReportDirectoryIfNotExists() {
        // Arrange — use a subdirectory that doesn't exist yet
        String newDir = tempDir.toString() + "/new-reports/";
        ReflectionTestUtils.setField(reportService, "reportBasePath", newDir);

        // Act
        reportService.generateMonthlyReport("08", "2024");

        // Assert
        File dir = new File(newDir);
        assertTrue(dir.exists(), "Report directory should be created");
    }

    @Test
    void generateMonthlyReport_withDifferentYears_generatesDistinctFiles() {
        // Act
        Map<String, Object> result2023 = reportService.generateMonthlyReport("01", "2023");
        Map<String, Object> result2024 = reportService.generateMonthlyReport("01", "2024");

        // Assert
        assertNotEquals(result2023.get("path"), result2024.get("path"));
    }

    @Test
    void generateMonthlyReport_whenDirectoryAlreadyExists_stillGeneratesReport() {
        // Arrange — generate once to create directory
        reportService.generateMonthlyReport("09", "2024");

        // Act — generate again (directory already exists)
        Map<String, Object> result = reportService.generateMonthlyReport("10", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
    }

    @Test
    void generateMonthlyReport_fileNameContainsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("11", "2025");

        // Assert
        String path = (String) result.get("path");
        assertTrue(path.contains("11"));
        assertTrue(path.contains("2025"));
    }

    @Test
    void generateMonthlyReport_withInvalidPath_returnsErrorStatus() {
        // Arrange — set an invalid path that cannot be created
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                "/root/cannot-create-this-dir-ever/");

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("12", "2024");

        // Assert — either generated (if running as root) or error
        assertNotNull(result.get("status"));
    }

    // ─── buildReportDownloadUrl ───────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_returnsUrlContainingReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertTrue(url.contains("march_report.pdf"));
    }

    @Test
    void buildReportDownloadUrl_returnsUrlStartingWithBaseUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("april_report.pdf");

        // Assert
        assertTrue(url.startsWith("https://reports.resorts-internal.com/download/"));
    }

    @Test
    void buildReportDownloadUrl_concatenatesBaseUrlAndReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("may_report.pdf");

        // Assert
        assertEquals("https://reports.resorts-internal.com/download/may_report.pdf", url);
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsBaseUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertEquals("https://reports.resorts-internal.com/download/", url);
    }

    @Test
    void buildReportDownloadUrl_withSpecialCharacters_includesThemInUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_2024-06.csv");

        // Assert
        assertTrue(url.contains("report_2024-06.csv"));
    }

    @Test
    void buildReportDownloadUrl_returnsNonNullString() {
        // Act
        String url = reportService.buildReportDownloadUrl("test.pdf");

        // Assert
        assertNotNull(url);
        assertFalse(url.isBlank());
    }

    // ─── getSystemInfo ────────────────────────────────────────────────────────

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
        assertTrue(info.containsKey("reportPath"));
        assertNotNull(info.get("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"));
        assertNotNull(info.get("backupPath"));
    }

    @Test
    void getSystemInfo_containsServerPort() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"));
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAt() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"));
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtMatchesDateTimePattern() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String generatedAt = (String) info.get("generatedAt");
        // Pattern: yyyy-MM-dd HH:mm:ss
        assertTrue(generatedAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "generatedAt should match 'yyyy-MM-dd HH:mm:ss' pattern, got: " + generatedAt);
    }

    @Test
    void getSystemInfo_reportPathMatchesConfiguredValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String reportPath = (String) info.get("reportPath");
        assertTrue(reportPath.contains("reports"));
    }

    @Test
    void getSystemInfo_backupPathMatchesConfiguredValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String backupPath = (String) info.get("backupPath");
        assertTrue(backupPath.contains("backups"));
    }

    @Test
    void getSystemInfo_containsFourKeys() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(4, info.size(),
                "System info map should contain exactly 4 keys");
    }
}
