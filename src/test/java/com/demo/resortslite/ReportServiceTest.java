package com.demo.resortslite;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    private final ReportService reportService = new ReportService();

    @Test
    void generateMonthlyReport_shouldCreateReportFileAndReturnMetadata() throws Exception {
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        assertEquals("generated", result.get("status"));
        assertEquals("reports/resort_report_03_2024.csv", result.get("path"));
        assertEquals(0, result.get("serverPort"));
        assertTrue(Files.exists(Path.of("reports/resort_report_03_2024.csv")));
    }

    @Test
    void buildReportDownloadUrl_shouldPrefixDownloadPath() {
        String url = reportService.buildReportDownloadUrl("monthly.csv");

        assertEquals("/download/monthly.csv", url);
    }

    @Test
    void getSystemInfo_shouldContainConfiguredPathsAndTimestamp() {
        Map<String, Object> info = reportService.getSystemInfo();

        assertEquals("reports/", info.get("reportPath"));
        assertEquals("backups/nightly/", info.get("backupPath"));
        assertEquals(0, info.get("serverPort"));
        assertNotNull(info.get("generatedAt"));
        assertFalse(info.get("generatedAt").toString().isBlank());
    }
}
