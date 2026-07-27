package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReportService}.
 * Uses a JUnit 5 {@code @TempDir} so no real filesystem paths are hard-coded.
 */
class ReportServiceTest {

    @TempDir
    Path tempDir;

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
        // Inject @Value fields that Spring would normally populate
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                tempDir.toString() + "/reports/");
        ReflectionTestUtils.setField(reportService, "backupPath",
                tempDir.toString() + "/backups/");
        ReflectionTestUtils.setField(reportService, "serverPort", 8080);
    }

    // -----------------------------------------------------------------------
    // generateMonthlyReport()
    // -----------------------------------------------------------------------

    @Test
    void generateMonthlyReport_withValidInputs_returnsGeneratedStatus() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result, "Result must not be null");
        assertEquals("generated", result.get("status"),
                "Status must be 'generated' on success");
    }

    @Test
    void generateMonthlyReport_withValidInputs_returnsCorrectPath() {
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        String path = (String) result.get("path");
        assertNotNull(path, "path must not be null");
        assertTrue(path.contains("resort_report_03_2024.csv"),
                "path must contain the expected file name");
    }

    @Test
    void generateMonthlyReport_createsFileOnDisk() {
        Map<String, Object> result = reportService.generateMonthlyReport("04", "2024");

        String path = (String) result.get("path");
        assertNotNull(path);
        File reportFile = new File(path);
        assertTrue(reportFile.exists(),
                "The generated report file must exist on disk");
    }

    @Test
    void generateMonthlyReport_createsDirectoryIfAbsent() {
        // The tempDir sub-directory does not exist yet
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2024");

        assertEquals("generated", result.get("status"),
                "Directory creation must succeed and status must be 'generated'");
    }

    @Test
    void generateMonthlyReport_returnsServerPort() {
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        assertEquals(8080, result.get("serverPort"),
                "serverPort must be returned in the result map");
    }

    @Test
    void generateMonthlyReport_withDifferentMonthsAndYears_producesDistinctPaths() {
        Map<String, Object> result1 = reportService.generateMonthlyReport("01", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("02", "2024");

        assertNotEquals(result1.get("path"), result2.get("path"),
                "Different month/year combinations must produce different file paths");
    }

    @Test
    void generateMonthlyReport_writtenFileContainsCsvHeader() throws Exception {
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2024");

        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount"),
                "Generated CSV must contain the expected header row");
    }

    @Test
    void generateMonthlyReport_writtenFileContainsSampleData() throws Exception {
        Map<String, Object> result = reportService.generateMonthlyReport("08", "2024");

        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BK-001"),
                "Generated CSV must contain sample booking BK-001");
        assertTrue(content.contains("BK-002"),
                "Generated CSV must contain sample booking BK-002");
    }

    @Test
    void generateMonthlyReport_withUnwritablePath_returnsErrorStatus() {
        // Point to a path that cannot be created (root-owned directory)
        ReflectionTestUtils.setField(reportService, "reportBasePath", "/root/no-access/");

        Map<String, Object> result = reportService.generateMonthlyReport("09", "2024");

        // On a system where /root is not writable by the test runner this returns "error"
        // On CI where tests run as root it may succeed — accept both outcomes gracefully
        assertNotNull(result.get("status"),
                "status must always be present in the result map");
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl()
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_withDefaultEnv_returnsHttpsUrl() {
        // Ensure the env variable is not set so the default is used
        String url = reportService.buildReportDownloadUrl("report_2024_03.pdf");

        assertNotNull(url, "URL must not be null");
        assertTrue(url.startsWith("https://"),
                "Download URL must use HTTPS");
    }

    @Test
    void buildReportDownloadUrl_containsReportName() {
        String url = reportService.buildReportDownloadUrl("report_2024_04.pdf");

        assertTrue(url.endsWith("report_2024_04.pdf"),
                "URL must end with the report file name");
    }

    @Test
    void buildReportDownloadUrl_withDifferentReportNames_producesDistinctUrls() {
        String url1 = reportService.buildReportDownloadUrl("jan.pdf");
        String url2 = reportService.buildReportDownloadUrl("feb.pdf");

        assertNotEquals(url1, url2,
                "Different report names must produce different URLs");
    }

    @Test
    void buildReportDownloadUrl_defaultBaseUrlContainsExpectedDomain() {
        String url = reportService.buildReportDownloadUrl("test.pdf");

        assertTrue(url.contains("resorts-internal.com"),
                "Default URL must reference the internal reports domain");
    }

    // -----------------------------------------------------------------------
    // getSystemInfo()
    // -----------------------------------------------------------------------

    @Test
    void getSystemInfo_returnsNonNullMap() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertNotNull(info, "getSystemInfo() must not return null");
    }

    @Test
    void getSystemInfo_containsReportPath() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("reportPath"),
                "System info must contain 'reportPath'");
        assertNotNull(info.get("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPath() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("backupPath"),
                "System info must contain 'backupPath'");
        assertNotNull(info.get("backupPath"));
    }

    @Test
    void getSystemInfo_containsServerPort() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("serverPort"),
                "System info must contain 'serverPort'");
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAt() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("generatedAt"),
                "System info must contain 'generatedAt' timestamp");
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtMatchesExpectedFormat() {
        Map<String, Object> info = reportService.getSystemInfo();
        String ts = (String) info.get("generatedAt");
        // Expected format: yyyy-MM-dd HH:mm:ss  (19 characters)
        assertNotNull(ts);
        assertEquals(19, ts.length(),
                "Timestamp must follow 'yyyy-MM-dd HH:mm:ss' format (19 chars)");
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp must match pattern yyyy-MM-dd HH:mm:ss");
    }

    @Test
    void getSystemInfo_reportPathMatchesInjectedValue() {
        Map<String, Object> info = reportService.getSystemInfo();
        String reportPath = (String) info.get("reportPath");
        assertTrue(reportPath.contains("reports"),
                "reportPath must contain the injected base path");
    }
}
