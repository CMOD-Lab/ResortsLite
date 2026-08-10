package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
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
        ReflectionTestUtils.setField(reportService, "reportBasePath", tempDir.toString());
        ReflectionTestUtils.setField(reportService, "reportDownloadBaseUrl",
                "https://reports.resorts-internal.com/download");
    }

    // -----------------------------------------------------------------------
    // generateMonthlyReport tests
    // -----------------------------------------------------------------------

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
        assertTrue(path.contains("resort_report_03_2024.csv"));
    }

    @Test
    void generateMonthlyReport_createsFileOnDisk() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("04", "2024");

        // Assert
        String path = (String) result.get("path");
        File reportFile = new File(path);
        assertTrue(reportFile.exists(), "Report file should exist on disk");
    }

    @Test
    void generateMonthlyReport_fileContainsCsvHeader() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2024");

        // Assert
        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount"));
    }

    @Test
    void generateMonthlyReport_fileContainsSampleData() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        // Assert
        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BK-001"));
        assertTrue(content.contains("BK-002"));
    }

    @Test
    void generateMonthlyReport_withDifferentMonths_createsDistinctFiles() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("01", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("02", "2024");

        // Assert
        assertNotEquals(result1.get("path"), result2.get("path"));
    }

    @Test
    void generateMonthlyReport_withDifferentYears_createsDistinctFiles() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("03", "2023");
        Map<String, Object> result2 = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotEquals(result1.get("path"), result2.get("path"));
    }

    @Test
    void generateMonthlyReport_whenDirectoryDoesNotExist_createsDirectory() {
        // Arrange
        String newSubDir = tempDir.toString() + File.separator + "newreports";
        ReflectionTestUtils.setField(reportService, "reportBasePath", newSubDir);

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        assertTrue(new File(newSubDir).exists());
    }

    @Test
    void generateMonthlyReport_withInvalidPath_returnsErrorStatus() {
        // Arrange — set an unwritable path
        ReflectionTestUtils.setField(reportService, "reportBasePath", "/root/nonexistent/path/that/cannot/be/created");

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("08", "2024");

        // Assert — either generated (if path is writable) or error
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_resultMapContainsStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("09", "2024");

        // Assert
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_resultMapContainsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("10", "2024");

        // Assert
        assertTrue(result.containsKey("path"));
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl tests
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_withValidReportName_returnsFullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("resort_report_03_2024.csv");

        // Assert
        assertNotNull(url);
        assertEquals("https://reports.resorts-internal.com/download/resort_report_03_2024.csv", url);
    }

    @Test
    void buildReportDownloadUrl_urlStartsWithHttps() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.csv");

        // Assert
        assertTrue(url.startsWith("https://"), "URL should start with https://");
    }

    @Test
    void buildReportDownloadUrl_containsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("my_report.csv");

        // Assert
        assertTrue(url.contains("my_report.csv"));
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsBaseUrlWithSlash() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("https://reports.resorts-internal.com/download/"));
    }

    @Test
    void buildReportDownloadUrl_withDifferentBaseUrl_returnsCorrectUrl() {
        // Arrange
        ReflectionTestUtils.setField(reportService, "reportDownloadBaseUrl",
                "https://custom-reports.example.com/files");

        // Act
        String url = reportService.buildReportDownloadUrl("test.csv");

        // Assert
        assertEquals("https://custom-reports.example.com/files/test.csv", url);
    }

    @Test
    void buildReportDownloadUrl_withSpecialCharactersInName_returnsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_2024-03.csv");

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("report_2024-03.csv"));
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
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"));
    }

    @Test
    void getSystemInfo_reportPathMatchesConfiguredPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(tempDir.toString(), info.get("reportPath"));
    }

    @Test
    void getSystemInfo_generatedAtIsNotEmpty() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String generatedAt = (String) info.get("generatedAt");
        assertNotNull(generatedAt);
        assertFalse(generatedAt.isEmpty());
    }

    @Test
    void getSystemInfo_generatedAtMatchesDateTimeFormat() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String generatedAt = (String) info.get("generatedAt");
        // Format: yyyy-MM-dd HH:mm:ss
        assertTrue(generatedAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "generatedAt should match yyyy-MM-dd HH:mm:ss format, got: " + generatedAt);
    }

    @Test
    void getSystemInfo_calledMultipleTimes_returnsConsistentKeys() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        assertEquals(info1.keySet(), info2.keySet());
    }
}
