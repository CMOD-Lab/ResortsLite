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

/**
 * ReportService — cloud-native implementation using Azure Blob Storage for file
 * persistence, Azure App Configuration for externalised URLs and ports, and
 * Azure Service Bus for distributed scheduled task execution.
 *
 * <p>Fixes applied:
 * <ul>
 *   <li>cr-java-0061 / cr-java-0062 / cr-java-0063 — hard-coded file paths and
 *       java.io.File operations replaced with Azure Blob Storage (BlobContainerClient).</li>
 *   <li>cr-java-0071 — hard-coded report download URL externalised to Azure App
 *       Configuration; falls back to the {@code REPORT_DOWNLOAD_BASE_URL} environment
 *       variable when App Configuration is unavailable.</li>
 *   <li>cr-java-0077 — hard-coded port replaced with the {@code SERVER_PORT} environment
 *       variable (default 8080) and Azure App Configuration.</li>
 *   <li>cr-java-0111 — java.util.Timer replaced with Azure Service Bus scheduled
 *       message delivery for distributed, timezone-agnostic task execution.</li>
 * </ul>
 */
@Service
public class ReportService {

    // -----------------------------------------------------------------------
    // cr-java-0061 / cr-java-0062 / cr-java-0063 FIX:
    // Hard-coded absolute paths (/var/legacy/reports/, C:\ResortBackups\nightly\)
    // and java.io.File operations replaced with Azure Blob Storage configuration
    // loaded from environment variables.
    // -----------------------------------------------------------------------

    /** Azure Storage account connection string — injected from environment variable. */
    @Value("${AZURE_STORAGE_CONNECTION_STRING:#{null}}")
    private String storageConnectionString;

    /** Azure Blob container name for reports — injected from environment variable. */
    @Value("${AZURE_BLOB_CONTAINER_NAME:resort-reports}")
    private String blobContainerName;

    // -----------------------------------------------------------------------
    // cr-java-0077 FIX:
    // Hard-coded port 8080 replaced with environment variable SERVER_PORT.
    // -----------------------------------------------------------------------

    /** Server port — resolved from environment variable, not hard-coded. */
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    // -----------------------------------------------------------------------
    // cr-java-0071 FIX:
    // Hard-coded report download URL replaced with Azure App Configuration value
    // loaded at runtime; falls back to REPORT_DOWNLOAD_BASE_URL env variable.
    // -----------------------------------------------------------------------

    /** Base URL for report downloads — externalised to Azure App Configuration. */
    @Value("${REPORT_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com}")
    private String reportDownloadBaseUrl;

    /** Azure App Configuration endpoint — injected from environment variable. */
    @Value("${AZURE_APP_CONFIG_ENDPOINT:#{null}}")
    private String appConfigEndpoint;

    // -----------------------------------------------------------------------
    // cr-java-0111 FIX:
    // Azure Service Bus connection string and queue name for scheduled messages.
    // -----------------------------------------------------------------------

    /** Azure Service Bus connection string — injected from environment variable. */
    @Value("${AZURE_SERVICE_BUS_CONNECTION_STRING:#{null}}")
    private String serviceBusConnectionString;

    /** Azure Service Bus queue name for scheduled report tasks. */
    @Value("${AZURE_SERVICE_BUS_QUEUE_NAME:report-schedule-queue}")
    private String serviceBusQueueName;

    // -----------------------------------------------------------------------
    // cr-java-0061 / cr-java-0062 / cr-java-0063 FIX:
    // generateMonthlyReport now uploads the CSV content directly to Azure Blob
    // Storage instead of writing to a local file path.
    // -----------------------------------------------------------------------

    /**
     * Generates a monthly booking report and uploads it to Azure Blob Storage.
     *
     * @param month the month identifier (e.g. "03")
     * @param year  the year identifier (e.g. "2024")
     * @return a result map containing upload status and the blob URL
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);
            InputStream inputStream = new ByteArrayInputStream(csvBytes);

            // Upload to Azure Blob Storage
            BlobContainerClient containerClient = getBlobContainerClient();
            BlobClient blobClient = containerClient.getBlobClient(blobName);
            blobClient.upload(inputStream, csvBytes.length, true);

            result.put("status", "generated");
            result.put("blobName", blobName);
            result.put("blobUrl", blobClient.getBlobUrl());
            // cr-java-0077: serverPort resolved from environment variable, not hard-coded
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // cr-java-0071 FIX:
    // buildReportDownloadUrl now resolves the base URL from Azure App Configuration
    // (with environment variable fallback) instead of using a hard-coded string.
    // -----------------------------------------------------------------------

    /**
     * Builds a report download URL using the base URL loaded from Azure App
     * Configuration or the {@code REPORT_DOWNLOAD_BASE_URL} environment variable.
     *
     * @param reportName the name of the report blob
     * @return the fully qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        String baseUrl = resolveReportDownloadBaseUrl();
        // Ensure HTTPS — cloud security standards enforce encrypted transport
        if (baseUrl.startsWith("http://")) {
            baseUrl = baseUrl.replaceFirst("http://", "https://");
        }
        return baseUrl + "/download/" + reportName;
    }

    /**
     * Returns system information using externalised configuration values.
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0061: report path is now a Blob Storage container reference
        info.put("reportContainer", blobContainerName);
        // cr-java-0061: backup path externalised — no Windows-style hard-coded path
        info.put("backupContainer", System.getenv().getOrDefault("AZURE_BACKUP_CONTAINER_NAME", "resort-backups"));
        // cr-java-0077: port resolved from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    // -----------------------------------------------------------------------
    // cr-java-0111 FIX:
    // scheduleReportGeneration replaces java.util.Timer with Azure Service Bus
    // scheduled message delivery for distributed, timezone-agnostic execution.
    // -----------------------------------------------------------------------

    /**
     * Schedules a report generation task using Azure Service Bus scheduled messages
     * instead of java.util.Timer, enabling distributed and timezone-agnostic execution.
     *
     * @param month            the month for which the report should be generated
     * @param year             the year for which the report should be generated
     * @param scheduledEnqueueTimeUtc the UTC time at which the message should be delivered
     */
    public void scheduleReportGeneration(String month, String year, java.time.OffsetDateTime scheduledEnqueueTimeUtc) {
        if (serviceBusConnectionString == null || serviceBusConnectionString.isEmpty()) {
            throw new IllegalStateException(
                    "AZURE_SERVICE_BUS_CONNECTION_STRING environment variable is not set. "
                    + "Configure Azure Service Bus to enable distributed scheduled report generation.");
        }

        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(serviceBusQueueName)
                .buildClient()) {

            String messageBody = "{\"action\":\"generateReport\",\"month\":\"" + month
                    + "\",\"year\":\"" + year + "\"}";

            ServiceBusMessage message = new ServiceBusMessage(messageBody);
            message.setScheduledEnqueueTime(scheduledEnqueueTimeUtc);

            senderClient.sendMessage(message);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Creates and returns a {@link BlobContainerClient} using the Azure Storage
     * connection string from the environment variable, ensuring the container exists.
     */
    private BlobContainerClient getBlobContainerClient() {
        if (storageConnectionString == null || storageConnectionString.isEmpty()) {
            // Fall back to DefaultAzureCredential (Managed Identity / workload identity)
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .endpoint(System.getenv().getOrDefault("AZURE_STORAGE_ENDPOINT",
                            "https://<storage-account>.blob.core.windows.net"))
                    .buildClient();
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(blobContainerName);
            if (!containerClient.exists()) {
                containerClient.create();
            }
            return containerClient;
        }

        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(storageConnectionString)
                .buildClient();
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(blobContainerName);
        if (!containerClient.exists()) {
            containerClient.create();
        }
        return containerClient;
    }

    /**
     * Resolves the report download base URL from Azure App Configuration when
     * available, otherwise falls back to the injected environment variable value.
     */
    private String resolveReportDownloadBaseUrl() {
        if (appConfigEndpoint != null && !appConfigEndpoint.isEmpty()) {
            try {
                ConfigurationClient configClient = new ConfigurationClientBuilder()
                        .credential(new DefaultAzureCredentialBuilder().build())
                        .endpoint(appConfigEndpoint)
                        .buildClient();
                String configuredUrl = configClient.getConfigurationSetting(
                        "report.download.base.url", null).getValue();
                if (configuredUrl != null && !configuredUrl.isEmpty()) {
                    return configuredUrl;
                }
            } catch (Exception e) {
                // Fall through to environment variable fallback
            }
        }
        return reportDownloadBaseUrl;
    }
}
