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
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

@Service
public class ReportService {

    // cr-java-0061 / cr-java-0062 / cr-java-0063: Hard-coded file paths and local file
    // write operations replaced with Azure Blob Storage configuration via environment variables.
    @Value("${azure.storage.blob.endpoint:#{environment['AZURE_STORAGE_BLOB_ENDPOINT']}}")
    private String blobEndpoint;

    @Value("${azure.storage.blob.container-name:reports}")
    private String containerName;

    // cr-java-0077: Hard-coded port replaced with Azure App Configuration / environment variable.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0071: Hard-coded environment URL replaced with Azure App Configuration value.
    @Value("${app.report.download.base-url:${REPORT_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com}}")
    private String reportDownloadBaseUrl;

    // cr-java-0111: Azure Service Bus connection string for scheduled message delivery.
    @Value("${azure.servicebus.connection-string:#{environment['AZURE_SERVICEBUS_CONNECTION_STRING']}}")
    private String serviceBusConnectionString;

    @Value("${azure.servicebus.queue-name:report-schedule-queue}")
    private String serviceBusQueueName;

    /**
     * Generates a monthly report and uploads it to Azure Blob Storage.
     * Replaces local file system write operations (cr-java-0061, cr-java-0062, cr-java-0063).
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return a map containing the operation status and blob URL
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));
            writer.println("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount");
            writer.println("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00");
            writer.println("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00");
            writer.flush();

            byte[] csvBytes = baos.toByteArray();

            // Upload to Azure Blob Storage using DefaultAzureCredential (managed identity)
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
            result.put("blobUrl", blobClient.getBlobUrl());
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the externalized base URL from Azure App Configuration.
     * Replaces hard-coded environment URL (cr-java-0071).
     *
     * @param reportName the name of the report blob
     * @return the HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071: URL is now loaded from Azure App Configuration / environment variable
        return reportDownloadBaseUrl + "/download/" + reportName;
    }

    /**
     * Returns system information using externalized configuration values.
     * Replaces hard-coded paths and port (cr-java-0061, cr-java-0077).
     *
     * @return a map of system information
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111: Use UTC timezone for cloud-agnostic timestamp generation
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
     * Replaces java.util.Timer local scheduling (cr-java-0111).
     *
     * @param reportName the name of the report to schedule
     * @param scheduledEnqueueTimeUtc the UTC time to enqueue the message
     */
    public void scheduleReportGeneration(String reportName, java.time.OffsetDateTime scheduledEnqueueTimeUtc) {
        // cr-java-0111: Azure Service Bus scheduled message replaces java.util.Timer
        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(serviceBusQueueName)
                .buildClient()) {

            ServiceBusMessage message = new ServiceBusMessage(reportName);
            message.setScheduledEnqueueTime(scheduledEnqueueTimeUtc);
            senderClient.sendMessage(message);
        }
    }
}
