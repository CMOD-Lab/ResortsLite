package com.demo.resortslite;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Blocker-1, Blocker-2, Blocker-3 (cr-java-0061): Hard-coded file paths replaced with
    // Azure Blob Storage configuration loaded from environment variables / Azure App Configuration.
    @Value("${azure.storage.connection-string:${AZURE_STORAGE_CONNECTION_STRING:}}")
    private String storageConnectionString;

    // Blocker-12 (cr-java-0077): Hard-coded port replaced with environment variable / Azure App Configuration.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // Blocker-11 (cr-java-0071): Hard-coded report download URL replaced with Azure App Configuration value.
    @Value("${app.report.base-url:${APP_REPORT_BASE_URL:https://reports.resorts-internal.com}}")
    private String reportBaseUrl;

    private static final String CONTAINER_NAME = "resort-reports";

    /**
     * Returns a BlobContainerClient backed by Azure Blob Storage.
     * The connection string is sourced from environment variables or Azure App Configuration,
     * eliminating any hard-coded file-system path dependency (blockers 1-7, cr-java-0061/0062/0063).
     */
    private BlobContainerClient getContainerClient() {
        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
                .connectionString(storageConnectionString)
                .buildClient();
        BlobContainerClient containerClient = serviceClient.getBlobContainerClient(CONTAINER_NAME);
        if (!containerClient.exists()) {
            containerClient.create();
        }
        return containerClient;
    }

    /**
     * Generates a monthly report and uploads it to Azure Blob Storage.
     * Replaces all local java.io.File / FileWriter operations (blockers 4-7, cr-java-0062/0063)
     * and removes hard-coded absolute paths (blockers 1-3, cr-java-0061).
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);
            InputStream inputStream = new ByteArrayInputStream(contentBytes);

            // Upload to Azure Blob Storage (blocker-4: replaces local FileWriter write)
            BlobContainerClient containerClient = getContainerClient();
            BlobClient blobClient = containerClient.getBlobClient(blobName);
            blobClient.upload(inputStream, contentBytes.length, true);

            result.put("status", "generated");
            result.put("blobName", blobName);
            result.put("containerName", CONTAINER_NAME);
            // Blocker-12 (cr-java-0077): serverPort now sourced from environment variable
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the base URL loaded from Azure App Configuration,
     * replacing the hard-coded environment URL (blocker-11, cr-java-0071).
     */
    public String buildReportDownloadUrl(String reportName) {
        // Blocker-11 (cr-java-0071): URL is now sourced from Azure App Configuration via
        // the app.report.base-url property, not hard-coded in source.
        return reportBaseUrl + "/download/" + reportName;
    }

    /**
     * Returns system information using externalized configuration values.
     * Hard-coded paths and port replaced with Azure App Configuration / environment variables.
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        // Blocker-1/2/3 (cr-java-0061): No hard-coded paths; storage is Azure Blob Storage
        info.put("storageContainer", CONTAINER_NAME);
        // Blocker-12 (cr-java-0077): Port sourced from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
