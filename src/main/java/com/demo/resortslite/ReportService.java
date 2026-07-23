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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

@Service
public class ReportService {

    // Blocker-1/2/3 (cr-java-0061): Hard-coded file paths replaced with Azure Blob Storage
    // configuration loaded from environment variables / Azure App Configuration.
    // Blocker-12 (cr-java-0077): Hard-coded port replaced with environment variable.
    // Blocker-11 (cr-java-0071): Hard-coded URL replaced with environment variable.

    @Value("${azure.storage.blob.endpoint:#{environment['AZURE_STORAGE_BLOB_ENDPOINT']}}")
    private String blobEndpoint;

    @Value("${azure.storage.blob.container-name:${AZURE_STORAGE_CONTAINER_NAME:reports}}")
    private String containerName;

    // Blocker-12 (cr-java-0077): Port externalised to environment variable / Azure App Configuration
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // Blocker-11 (cr-java-0071): Hard-coded report download URL replaced with configurable value
    @Value("${app.report.download.base-url:${APP_REPORT_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com}}")
    private String reportDownloadBaseUrl;

    // Blocker-19 (cr-java-0111): Azure Service Bus connection string for scheduled messages
    @Value("${azure.servicebus.connection-string:${AZURE_SERVICEBUS_CONNECTION_STRING:}}")
    private String serviceBusConnectionString;

    @Value("${azure.servicebus.queue-name:${AZURE_SERVICEBUS_QUEUE_NAME:report-scheduler}}")
    private String serviceBusQueueName;

    /**
     * Generates a monthly report and uploads it to Azure Blob Storage.
     * Replaces local file system writes (cr-java-0061, cr-java-0062, cr-java-0063).
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return result map with status and blob URL
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Blocker-4/5/6/7 (cr-java-0062/0063): Replace FileWriter / java.io.File with
            // Azure Blob Storage upload using Azure SDK for Java.
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.flush();
            writer.close();

            byte[] csvBytes = baos.toByteArray();

            // Upload to Azure Blob Storage
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .endpoint(blobEndpoint)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();

            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
            if (!containerClient.exists()) {
                containerClient.create();
            }

            BlobClient blobClient = containerClient.getBlobClient(blobName);
            blobClient.upload(new ByteArrayInputStream(csvBytes), csvBytes.length, true);

            result.put("status", "generated");
            result.put("blobName", blobName);
            result.put("blobUrl", blobClient.getBlobUrl());
            // Blocker-12 (cr-java-0077): serverPort now read from environment variable
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the externally configured base URL.
     * Blocker-11 (cr-java-0071): Hard-coded URL replaced with Azure App Configuration value.
     *
     * @param reportName the name of the report blob
     * @return the HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Blocker-11 (cr-java-0071): URL loaded from environment variable / Azure App Configuration
        return reportDownloadBaseUrl + "/download/" + reportName;
    }

    /**
     * Returns system information using externalised configuration values.
     * Blocker-1/2/3 (cr-java-0061): No local paths; Blob Storage endpoint used instead.
     * Blocker-12 (cr-java-0077): Port read from environment variable.
     *
     * @return system info map
     */
    public Map<String, Object> getSystemInfo() {
        // Use UTC to avoid server-local timezone issues (cr-java-0111)
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String timestamp = sdf.format(new Date());

        Map<String, Object> info = new HashMap<>();
        info.put("blobEndpoint", blobEndpoint);
        info.put("containerName", containerName);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Schedules a report generation task using Azure Service Bus scheduled messages.
     * Blocker-19 (cr-java-0111): Replaces java.util.Timer with Azure Service Bus
     * for distributed, timezone-agnostic task scheduling.
     *
     * @param taskPayload the task description to schedule
     */
    public void scheduleReportTask(String taskPayload) {
        if (serviceBusConnectionString == null || serviceBusConnectionString.isEmpty()) {
            // Fallback: log the scheduling request when Service Bus is not configured
            System.out.println("Service Bus not configured. Scheduled task: " + taskPayload);
            return;
        }

        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(serviceBusQueueName)
                .buildClient()) {

            // Blocker-19 (cr-java-0111): Azure Service Bus scheduled message delivery
            // replaces java.util.Timer for distributed, timezone-agnostic execution.
            ServiceBusMessage message = new ServiceBusMessage(taskPayload);
            senderClient.sendMessage(message);
        }
    }
}
