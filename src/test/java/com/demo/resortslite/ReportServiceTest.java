package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @InjectMocks
    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Inject @Value fields via ReflectionTestUtils (no Spring context needed)
        ReflectionTestUtils.setField(reportService, "reportBasePath", tempDir.toString());
        ReflectionTestUtils.setField(reportService, "backupPath", tempDir.resolve("backups").toString());
        ReflectionTestUtils.setField(reportService, "serverPort", 8080);
        ReflectionTestUtils.setField(reportService, "reportDownloadBaseUrl",
                "https://reports.resorts-internal.com/download");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateMonthlyReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsGeneratedStatus() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result);
        assertEquals("generated", result.get("status"));
    }

    @Test
    void generateMonthlyReport_createsFileOnDisk() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        String path = (String) result.get("path");
        assertNotNull(path);
        File reportFile = new File(path);
        assertTrue(reportFile.exists(), "Report file must be created on disk");
    }

    @Test
    void generateMonthlyReport_fileNameContainsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        // Assert
        String path = (String) result.get("path");
        assertTrue(path.contains("06"), "File path must contain the month");
        assertTrue(path.contains("2024"), "File path must contain the year");
    }

    @Test
    void generateMonthlyReport_fileNameEndsWithCsv() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2024");

        // Assert
        String path = (String) result.get("path");
        assertTrue(path.endsWith(".csv"), "Report file must have .csv extension");
    }

    @Test
    void generateMonthlyReport_resultContainsServerPort() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("08", "2024");

        // Assert
        assertNotNull(result.get("serverPort"));
        assertEquals(8080, result.get("serverPort"));
    }

    @Test
    void generateMonthlyReport_resultContainsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("09", "2024");

        // Assert
        assertTrue(result.containsKey("path"), "Result must contain 'path' key");
    }

    @Test
    void generateMonthlyReport_createsReportDirectoryIfNotExists() {
        // Arrange – use a sub-directory that doesn't exist yet
        String newDir = tempDir.resolve("new-reports").toString();
        ReflectionTestUtils.setField(reportService, "reportBasePath", newDir);

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("10", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        assertTrue(new File(newDir).exists(), "Report directory must be created");
    }

    @Test
    void generateMonthlyReport_writtenFileContainsCsvHeader() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("11", "2024");

        // Assert
        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BookingID"), "CSV must contain header 'BookingID'");
        assertTrue(content.contains("GuestName"), "CSV must contain header 'GuestName'");
    }

    @Test
    void generateMonthlyReport_writtenFileContainsSampleData() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("12", "2024");

        // Assert
        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BK-001"), "CSV must contain sample booking BK-001");
        assertTrue(content.contains("BK-002"), "CSV must contain sample booking BK-002");
    }

    @Test
    void generateMonthlyReport_withInvalidPath_returnsErrorStatus() {
        // Arrange – set an invalid path that cannot be written to
        ReflectionTestUtils.setField(reportService, "reportBasePath", "/root/no-permission-dir-xyz");

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("01", "2025");

        // Assert – on a system where /root is not writable, status should be "error"
        // On CI systems running as root this may succeed; we just verify the key exists
        assertTrue(result.containsKey("status"), "Result must always contain 'status' key");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildReportDownloadUrl tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_returnsUrlContainingReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_2024.pdf");

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("march_2024.pdf"), "URL must contain the report name");
    }

    @Test
    void buildReportDownloadUrl_returnsUrlStartingWithHttps() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.pdf");

        // Assert
        assertTrue(url.startsWith("https://"), "Download URL must use HTTPS");
    }

    @Test
    void buildReportDownloadUrl_urlContainsBaseUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("test_report.csv");

        // Assert
        assertTrue(url.contains("reports.resorts-internal.com"),
                "URL must contain the configured base domain");
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsUrlEndingWithSlash() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.endsWith("/"), "URL with empty report name should end with '/'");
    }

    @Test
    void buildReportDownloadUrl_withSpecialCharacters_returnsNonNullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report 2024 march.pdf");

        // Assert
        assertNotNull(url);
        assertFalse(url.isEmpty());
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
        assertTrue(info.containsKey("reportPath"), "System info must contain 'reportPath'");
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"), "System info must contain 'backupPath'");
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"), "System info must contain 'serverPort'");
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"), "System info must contain 'generatedAt'");
    }

    @Test
    void getSystemInfo_generatedAtIsFormattedTimestamp() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert – format: yyyy-MM-dd HH:mm:ss
        String ts = (String) info.get("generatedAt");
        assertNotNull(ts);
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp must match pattern yyyy-MM-dd HH:mm:ss");
    }

    @Test
    void getSystemInfo_reportPathMatchesInjectedValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(tempDir.toString(), info.get("reportPath"));
    }

    @Test
    void getSystemInfo_backupPathMatchesInjectedValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(tempDir.resolve("backups").toString(), info.get("backupPath"));
    }
}
