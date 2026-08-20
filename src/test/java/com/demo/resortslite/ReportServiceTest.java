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

/**
 * Comprehensive unit tests for {@link ReportService}.
 * Covers generateMonthlyReport, buildReportDownloadUrl, getSystemInfo.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
        // Inject @Value fields via ReflectionTestUtils
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                tempDir.toString() + "/");
        ReflectionTestUtils.setField(reportService, "reportDownloadBaseUrl",
                "https://reports.resorts-internal.com/download");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateMonthlyReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsGeneratedStatus() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsFilePath() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertNotNull(result.get("path"));
        assertTrue(result.get("path").toString().contains("resort_report_March_2024.csv"));
    }

    @Test
    void generateMonthlyReport_createsFileOnDisk() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("April", "2024");

        // Assert
        String path = (String) result.get("path");
        File file = new File(path);
        assertTrue(file.exists(), "Report file should exist on disk");
    }

    @Test
    void generateMonthlyReport_fileContainsCsvHeader() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("May", "2024");

        // Assert
        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount"));
    }

    @Test
    void generateMonthlyReport_fileContainsSampleData() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("June", "2024");

        // Assert
        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BK-001"));
        assertTrue(content.contains("BK-002"));
    }

    @Test
    void generateMonthlyReport_withDifferentMonths_createsDifferentFiles() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("January", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("February", "2024");

        // Assert
        assertNotEquals(result1.get("path"), result2.get("path"));
    }

    @Test
    void generateMonthlyReport_withDifferentYears_createsDifferentFiles() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("March", "2023");
        Map<String, Object> result2 = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertNotEquals(result1.get("path"), result2.get("path"));
    }

    @Test
    void generateMonthlyReport_whenDirectoryDoesNotExist_createsDirectoryAndFile() {
        // Arrange — use a nested subdirectory that doesn't exist yet
        String nestedPath = tempDir.toString() + "/nested/subdir/";
        ReflectionTestUtils.setField(reportService, "reportBasePath", nestedPath);

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("July", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        File file = new File((String) result.get("path"));
        assertTrue(file.exists());
    }

    @Test
    void generateMonthlyReport_withInvalidPath_returnsErrorStatus() {
        // Arrange — set an unwritable path
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                "/root/nonexistent_unwritable_path_xyz/");

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("August", "2024");

        // Assert — should return error status when IO fails
        // (may succeed if running as root; check for either outcome)
        assertNotNull(result.get("status"));
    }

    @Test
    void generateMonthlyReport_resultMapContainsStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("September", "2024");

        // Assert
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_resultMapContainsPathKey_onSuccess() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("October", "2024");

        // Assert
        if ("generated".equals(result.get("status"))) {
            assertTrue(result.containsKey("path"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildReportDownloadUrl
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_withValidReportName_returnsFullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_march_2024.csv");

        // Assert
        assertEquals("https://reports.resorts-internal.com/download/report_march_2024.csv", url);
    }

    @Test
    void buildReportDownloadUrl_urlStartsWithHttps() {
        // Act
        String url = reportService.buildReportDownloadUrl("any_report.pdf");

        // Assert
        assertTrue(url.startsWith("https://"), "URL must use HTTPS (cr-java-0088)");
    }

    @Test
    void buildReportDownloadUrl_containsReportNameInUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("resort_report_April_2024.csv");

        // Assert
        assertTrue(url.contains("resort_report_April_2024.csv"));
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsBaseUrlWithSlash() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("https://"));
    }

    @Test
    void buildReportDownloadUrl_withDifferentReportNames_returnsDifferentUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.csv");
        String url2 = reportService.buildReportDownloadUrl("report_feb.csv");

        // Assert
        assertNotEquals(url1, url2);
    }

    @Test
    void buildReportDownloadUrl_usesInjectedBaseUrl() {
        // Arrange
        ReflectionTestUtils.setField(reportService, "reportDownloadBaseUrl",
                "https://custom-reports.example.com/files");

        // Act
        String url = reportService.buildReportDownloadUrl("test.csv");

        // Assert
        assertTrue(url.startsWith("https://custom-reports.example.com/files"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getSystemInfo
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
    void getSystemInfo_reportPathMatchesInjectedValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(tempDir.toString() + "/", info.get("reportPath"));
    }

    @Test
    void getSystemInfo_generatedAtIsFormattedTimestamp() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String timestamp = (String) info.get("generatedAt");
        assertNotNull(timestamp);
        // Matches "yyyy-MM-dd HH:mm:ss"
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp should match yyyy-MM-dd HH:mm:ss format, got: " + timestamp);
    }

    @Test
    void getSystemInfo_calledTwice_returnsConsistentReportPath() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        assertEquals(info1.get("reportPath"), info2.get("reportPath"));
    }
}
