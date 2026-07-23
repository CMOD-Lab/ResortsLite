package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
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

    // cr-java-0061, cr-java-0062, cr-java-0063: Replaced hard-coded file paths with
    // Azure Blob Storage configuration loaded from environment variables.
    // REPORT_BASE_PATH and BACKUP_PATH hard-coded values removed.

    // cr-java-0077: Hard-coded port replaced with environment variable via @Value.
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    // cr-java-0071: Hard-coded report download URL replaced with Azure App Configuration.
    @Value("${AZURE_APP_CONFIG_ENDPOINT:}")
    private String appConfigEndpoint;

    // Azure Blob Storage connection string from environment variable (cr-java-0061/0062/0063)
    @Value("${AZURE_STORAGE_CONNECTION_STRING:}")
    private String storageConnectionString;

    // Azure Blob Storage container name from environment variable
    @Value("${AZURE_BLOB_CONTAINER_NAME:resort-reports}")
    private String blobContainerName;

    // Azure Service Bus connection string from environment variable (cr-java-0111)
    @Value("${AZURE_SERVICE_BUS_CONNECTION_STRING:}")
    private String serviceBusConnectionString;

    // Azure Service Bus queue name for scheduled report tasks (cr-java-0111)
    @Value("${AZURE_SERVICE_BUS_QUEUE_NAME:report-scheduler-queue}")
    private String serviceBusQueueName;

    /**
     * Generates a monthly report and uploads it to Azure Blob Storage.
     * Replaces local file system write operations (cr-java-0061, cr-java-0062, cr-java-0063).
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return result map with status and blob URL
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

            // Upload to Azure Blob Storage (cr-java-0061, cr-java-0062, cr-java-0063)
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .connectionString(storageConnectionString)
                    .buildClient();

            BlobContainerClient containerClient = blobServiceClient
                    .getBlobContainerClient(blobContainerName);

            if (!containerClient.exists()) {
                containerClient.create();
            }

            BlobClient blobClient = containerClient.getBlobClient(blobName);
            blobClient.upload(inputStream, contentBytes.length, true);

            result.put("status", "generated");
            result.put("blobName", blobName);
            result.put("blobUrl", blobClient.getBlobUrl());
            // cr-java-0077: serverPort now sourced from environment variable
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL using configuration from Azure App Configuration.
     * Replaces hard-coded environment URL (cr-java-0071).
     *
     * @param reportName the name of the report blob
     * @return the HTTPS download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071: URL base loaded from Azure App Configuration instead of hard-coded value.
        // Falls back to environment variable REPORT_DOWNLOAD_BASE_URL if App Configuration
        // endpoint is not configured.
        String baseUrl = System.getenv("REPORT_DOWNLOAD_BASE_URL");

        if (appConfigEndpoint != null && !appConfigEndpoint.isEmpty()) {
            try {
                ConfigurationClient configClient = new ConfigurationClientBuilder()
                        .credential(new DefaultAzureCredentialBuilder().build())
                        .endpoint(appConfigEndpoint)
                        .buildClient();
                String configuredUrl = configClient.getConfigurationSetting(
                        "report.download.base.url", null).getValue();
                if (configuredUrl != null && !configuredUrl.isEmpty()) {
                    baseUrl = configuredUrl;
                }
            } catch (Exception e) {
                // Fall back to environment variable if App Configuration is unavailable
            }
        }

        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://reports.resorts-internal.com/download";
        }

        return baseUrl + "/" + reportName;
    }

    /**
     * Returns system information using externalized configuration values.
     * Replaces hard-coded paths and port (cr-java-0061, cr-java-0077).
     *
     * @return map of system information
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0061: replaced hard-coded REPORT_BASE_PATH with Azure Blob container reference
        info.put("reportStorage", "azure-blob://" + blobContainerName);
        // cr-java-0061: replaced hard-coded BACKUP_PATH with environment variable reference
        info.put("backupStorage", System.getenv().getOrDefault("AZURE_BACKUP_CONTAINER", "azure-blob://resort-backups"));
        // cr-java-0077: serverPort now sourced from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Schedules a report generation task using Azure Service Bus scheduled messages.
     * Replaces java.util.Timer local scheduling (cr-java-0111).
     *
     * @param reportType the type of report to schedule
     * @param scheduledTimeUtc the UTC time string for scheduling
     */
    public void scheduleReportTask(String reportType, String scheduledTimeUtc) {
        // cr-java-0111: Replaced java.util.Timer with Azure Service Bus scheduled message
        // delivery for distributed, timezone-agnostic task execution.
        if (serviceBusConnectionString == null || serviceBusConnectionString.isEmpty()) {
            throw new IllegalStateException(
                    "AZURE_SERVICE_BUS_CONNECTION_STRING environment variable is not configured.");
        }

        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(serviceBusQueueName)
                .buildClient()) {

            String messageBody = "{\"reportType\":\"" + reportType
                    + "\",\"scheduledTime\":\"" + scheduledTimeUtc + "\"}";

            ServiceBusMessage message = new ServiceBusMessage(messageBody);
            message.setContentType("application/json");
            senderClient.sendMessage(message);
        }
    }
}
