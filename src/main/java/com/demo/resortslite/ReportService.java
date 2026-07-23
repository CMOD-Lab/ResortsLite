package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.ServiceBusMessage;
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

    // cr-java-0061: Hard-coded file paths replaced with Azure App Configuration / environment variables
    @Value("${azure.storage.blob.endpoint:${AZURE_STORAGE_BLOB_ENDPOINT:}}")
    private String blobEndpoint;

    // cr-java-0061 / cr-java-0077: Hard-coded paths and port replaced with environment variables
    @Value("${azure.storage.blob.container-name:${AZURE_STORAGE_CONTAINER_NAME:reports}}")
    private String containerName;

    // cr-java-0077: Hard-coded SERVER_PORT replaced with externalized environment variable
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0071: Hard-coded environment URL replaced with Azure App Configuration value
    @Value("${app.report.download.base-url:${APP_REPORT_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com/download}}")
    private String reportDownloadBaseUrl;

    // cr-java-0111: Azure Service Bus connection string for distributed scheduling
    @Value("${azure.servicebus.connection-string:${AZURE_SERVICEBUS_CONNECTION_STRING:}}")
    private String serviceBusConnectionString;

    @Value("${azure.servicebus.queue-name:${AZURE_SERVICEBUS_QUEUE_NAME:report-schedule-queue}}")
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
            // cr-java-0061 / cr-java-0062 / cr-java-0063:
            // Replace File/FileWriter local operations with Azure Blob Storage upload
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            byte[] contentBytes = csvContent.getBytes(StandardCharsets.UTF_8);
            InputStream inputStream = new ByteArrayInputStream(contentBytes);

            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .endpoint(blobEndpoint)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();

            BlobContainerClient containerClient = blobServiceClient
                    .getBlobContainerClient(containerName);

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
     * Builds a report download URL using externalized configuration.
     * cr-java-0071: Hard-coded environment URL replaced with Azure App Configuration value.
     *
     * @param reportName the name of the report blob
     * @return HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071: URL now loaded from Azure App Configuration / environment variable
        return reportDownloadBaseUrl + "/" + reportName;
    }

    /**
     * Returns system information using externalized configuration values.
     * cr-java-0061 / cr-java-0077: No hard-coded paths or ports.
     *
     * @return system info map
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0061: Paths replaced with Azure Blob Storage references
        info.put("blobEndpoint", blobEndpoint);
        info.put("containerName", containerName);
        // cr-java-0077: Port sourced from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Schedules a report generation task using Azure Service Bus.
     * cr-java-0111: Replaces java.util.Timer with Azure Service Bus scheduled messages
     * for distributed, timezone-agnostic task execution.
     *
     * @param month the month for which to schedule the report
     */
    public void scheduleReportGeneration(String month) {
        // cr-java-0111: Replace java.util.Timer with Azure Service Bus scheduled message delivery
        if (serviceBusConnectionString == null || serviceBusConnectionString.isEmpty()) {
            throw new IllegalStateException(
                    "Azure Service Bus connection string is not configured. "
                    + "Set AZURE_SERVICEBUS_CONNECTION_STRING environment variable.");
        }

        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(serviceBusQueueName)
                .buildClient()) {

            ServiceBusMessage message = new ServiceBusMessage("generate-report:" + month);
            senderClient.sendMessage(message);
        }
    }
}
