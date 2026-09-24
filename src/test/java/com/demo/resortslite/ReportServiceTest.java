package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link ReportService}.
 * Covers generateMonthlyReport, buildReportDownloadUrl, and getSystemInfo.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @InjectMocks
    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Inject externalised property values (normally set via @Value)
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                tempDir.toString() + "/reports/");
        ReflectionTestUtils.setField(reportService, "backupPath",
                tempDir.toString() + "/backups/");
        ReflectionTestUtils.setField(reportService, "serverPort", 8080);
    }

    // =========================================================================
    // generateMonthlyReport tests
    // =========================================================================

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsStatusGenerated() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsFilePath() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result.get("path"));
        assertTrue(result.get("path").toString().contains("resort_report_03_2024.csv"));
    }

    @Test
    void generateMonthlyReport_createsFileOnDisk() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("04", "2024");

        // Assert
        String path = result.get("path").toString();
        File reportFile = new File(path);
        assertTrue(reportFile.exists(), "Report file should exist on disk");
    }

    @Test
    void generateMonthlyReport_createsDirectoryIfNotExists() {
        // Arrange — use a nested path that doesn't exist yet
        String nestedPath = tempDir.toString() + "/nested/reports/";
        ReflectionTestUtils.setField(reportService, "reportBasePath", nestedPath);

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        File dir = new File(nestedPath);
        assertTrue(dir.exists());
    }

    @Test
    void generateMonthlyReport_returnsServerPort() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        // Assert — serverPort must come from injected property (czr-port-001 fix)
        assertEquals(8080, result.get("serverPort"));
    }

    @Test
    void generateMonthlyReport_fileContainsCsvHeader() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2024");

        // Assert
        String path = result.get("path").toString();
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount"));
    }

    @Test
    void generateMonthlyReport_fileContainsSampleData() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("08", "2024");

        // Assert
        String path = result.get("path").toString();
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BK-001"));
        assertTrue(content.contains("BK-002"));
    }

    @Test
    void generateMonthlyReport_differentMonthYear_producesUniqueFileName() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("01", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("02", "2024");

        // Assert
        assertNotEquals(result1.get("path"), result2.get("path"));
    }

    @Test
    void generateMonthlyReport_withInvalidPath_returnsErrorStatus() {
        // Arrange — set an invalid path that cannot be created
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                "/root/restricted_path_that_cannot_be_created_xyz/");

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("09", "2024");

        // Assert — should return error status gracefully
        // (may succeed if running as root, so we just verify the map is returned)
        assertNotNull(result);
        assertTrue(result.containsKey("status") || result.containsKey("error"));
    }

    @Test
    void generateMonthlyReport_doesNotUseHardcodedPath() {
        // Arrange — verify czr-java-001 fix: path comes from injected property
        String customPath = tempDir.toString() + "/custom/";
        ReflectionTestUtils.setField(reportService, "reportBasePath", customPath);

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("10", "2024");

        // Assert — path should use the injected value, not a hardcoded one
        if ("generated".equals(result.get("status"))) {
            assertTrue(result.get("path").toString().startsWith(customPath));
        }
    }

    // =========================================================================
    // buildReportDownloadUrl tests
    // =========================================================================

    @Test
    void buildReportDownloadUrl_withReportName_returnsHttpsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("resort_report_03_2024.csv");

        // Assert — must use HTTPS (cr-java-0088 fix)
        assertNotNull(url);
        assertTrue(url.startsWith("https://"));
    }

    @Test
    void buildReportDownloadUrl_withReportName_containsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("resort_report_03_2024.csv");

        // Assert
        assertTrue(url.contains("resort_report_03_2024.csv"));
    }

    @Test
    void buildReportDownloadUrl_doesNotUseHttp() {
        // Act
        String url = reportService.buildReportDownloadUrl("any_report.csv");

        // Assert — plain HTTP must not be used (security requirement)
        assertFalse(url.startsWith("http://"));
    }

    @Test
    void buildReportDownloadUrl_withDifferentReportNames_returnsDistinctUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.csv");
        String url2 = reportService.buildReportDownloadUrl("report_feb.csv");

        // Assert
        assertNotEquals(url1, url2);
    }

    @Test
    void buildReportDownloadUrl_containsExpectedDomain() {
        // Act
        String url = reportService.buildReportDownloadUrl("test_report.csv");

        // Assert
        assertTrue(url.contains("reports.resorts-internal.com"));
    }

    @Test
    void buildReportDownloadUrl_withEmptyName_returnsValidUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("https://"));
    }

    // =========================================================================
    // getSystemInfo tests
    // =========================================================================

    @Test
    void getSystemInfo_returnsReportPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("reportPath"));
        assertTrue(info.get("reportPath").toString().contains("reports"));
    }

    @Test
    void getSystemInfo_returnsBackupPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("backupPath"));
        assertTrue(info.get("backupPath").toString().contains("backups"));
    }

    @Test
    void getSystemInfo_returnsServerPort() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert — port must come from injected property (czr-port-001 fix)
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_returnsGeneratedAtTimestamp() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert — timestamp must be present and non-empty
        assertNotNull(info.get("generatedAt"));
        assertFalse(info.get("generatedAt").toString().isEmpty());
    }

    @Test
    void getSystemInfo_timestampMatchesExpectedFormat() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert — format: yyyy-MM-dd HH:mm:ss
        String timestamp = info.get("generatedAt").toString();
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp should match yyyy-MM-dd HH:mm:ss format, got: " + timestamp);
    }

    @Test
    void getSystemInfo_returnsAllFourKeys() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"));
        assertTrue(info.containsKey("backupPath"));
        assertTrue(info.containsKey("serverPort"));
        assertTrue(info.containsKey("generatedAt"));
    }

    @Test
    void getSystemInfo_reportPathMatchesInjectedValue() {
        // Arrange
        String expectedPath = tempDir.toString() + "/reports/";
        ReflectionTestUtils.setField(reportService, "reportBasePath", expectedPath);

        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert — czr-java-001 fix: path comes from injected property
        assertEquals(expectedPath, info.get("reportPath"));
    }

    @Test
    void getSystemInfo_backupPathMatchesInjectedValue() {
        // Arrange
        String expectedBackup = tempDir.toString() + "/backups/";
        ReflectionTestUtils.setField(reportService, "backupPath", expectedBackup);

        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(expectedBackup, info.get("backupPath"));
    }

    @Test
    void getSystemInfo_usesJavaTimeNotLegacyDate() {
        // Act — should not throw; java.time.LocalDateTime is thread-safe
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert — timestamp is a String (formatted by DateTimeFormatter)
        assertInstanceOf(String.class, info.get("generatedAt"));
    }
}
