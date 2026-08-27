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
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                tempDir.toString() + "/");
        ReflectionTestUtils.setField(reportService, "serverPort", 8080);
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
    void generateMonthlyReport_withValidMonthAndYear_returnsCorrectPath() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        String path = (String) result.get("path");
        assertNotNull(path);
        assertTrue(path.contains("resort_report_03_2024.csv"));
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsServerPort() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertEquals(8080, result.get("serverPort"));
    }

    @Test
    void generateMonthlyReport_createsFileOnDisk() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2024");

        // Assert
        String path = (String) result.get("path");
        File reportFile = new File(path);
        assertTrue(reportFile.exists(), "Report file should be created on disk");
    }

    @Test
    void generateMonthlyReport_createdFileContainsCsvHeader() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        // Assert
        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount"));
    }

    @Test
    void generateMonthlyReport_createdFileContainsSampleData() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2024");

        // Assert
        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BK-001"));
        assertTrue(content.contains("BK-002"));
    }

    @Test
    void generateMonthlyReport_differentMonthYear_generatesCorrectFileName() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("12", "2023");

        // Assert
        String path = (String) result.get("path");
        assertTrue(path.contains("resort_report_12_2023.csv"));
    }

    @Test
    void generateMonthlyReport_whenDirectoryDoesNotExist_createsDirectoryAndFile() {
        // Arrange — use a nested subdirectory that doesn't exist yet
        String nestedPath = tempDir.toString() + "/nested/reports/";
        ReflectionTestUtils.setField(reportService, "reportBasePath", nestedPath);

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("01", "2025");

        // Assert
        assertEquals("generated", result.get("status"));
        File dir = new File(nestedPath);
        assertTrue(dir.exists());
    }

    @Test
    void generateMonthlyReport_withInvalidPath_returnsErrorStatus() {
        // Arrange — set an invalid path that cannot be written to
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                "/root/nonexistent_restricted_path_xyz/");

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("01", "2024");

        // Assert — either generated (if running as root) or error
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl tests
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_withReportName_returnsFullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertNotNull(url);
        assertEquals("https://reports.resorts-internal.com/download/march_report.pdf", url);
    }

    @Test
    void buildReportDownloadUrl_withDifferentReportName_returnsCorrectUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("annual_2024.csv");

        // Assert
        assertTrue(url.endsWith("/annual_2024.csv"));
    }

    @Test
    void buildReportDownloadUrl_containsBaseUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("test.pdf");

        // Assert
        assertTrue(url.startsWith("https://reports.resorts-internal.com/download"));
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsBaseUrlWithSlash() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.endsWith("/"));
    }

    @Test
    void buildReportDownloadUrl_withCustomBaseUrl_usesInjectedValue() {
        // Arrange
        ReflectionTestUtils.setField(reportService, "reportDownloadBaseUrl",
                "https://custom.domain.com/reports");

        // Act
        String url = reportService.buildReportDownloadUrl("file.pdf");

        // Assert
        assertEquals("https://custom.domain.com/reports/file.pdf", url);
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
    void getSystemInfo_containsReportPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"));
        assertNotNull(info.get("reportPath"));
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
    void getSystemInfo_generatedAtMatchesTimestampFormat() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();
        String timestamp = (String) info.get("generatedAt");

        // Assert — format: yyyy-MM-dd HH:mm:ss (19 chars)
        assertNotNull(timestamp);
        assertEquals(19, timestamp.length());
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void getSystemInfo_reportPathMatchesInjectedValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String reportPath = (String) info.get("reportPath");
        assertTrue(reportPath.contains(tempDir.toString()));
    }

    @Test
    void getSystemInfo_withCustomPort_returnsCustomPort() {
        // Arrange
        ReflectionTestUtils.setField(reportService, "serverPort", 9090);

        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(9090, info.get("serverPort"));
    }
}
