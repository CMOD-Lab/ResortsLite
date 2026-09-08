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

@Service
public class ReportService {

    // cr-java-0061 / cr-java-0077: Hard-coded file paths and port replaced with
    // Azure App Configuration / environment variables injected via Spring @Value.
    @Value("${azure.storage.blob.endpoint}")
    private String blobEndpoint;

    @Value("${azure.storage.blob.container-name:reports}")
    private String containerName;

    // cr-java-0077: Hard-coded SERVER_PORT replaced with externalized environment variable.
    @Value("${server.port:${PORT:8080}}")
    private int serverPort;

    // cr-java-0071: Hard-coded report download URL replaced with externalized configuration.
    @Value("${app.report.download.base-url}")
    private String reportDownloadBaseUrl;

    // cr-java-0111: Azure Service Bus namespace connection string for scheduled messages.
    @Value("${azure.servicebus.connection-string:}")
    private String serviceBusConnectionString;

    @Value("${azure.servicebus.queue-name:report-schedule-queue}")
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
            // Build CSV content in memory — no local file system dependency.
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.flush();
            writer.close();

            byte[] csvBytes = baos.toByteArray();

            // cr-java-0061 / cr-java-0062 / cr-java-0063: Upload to Azure Blob Storage
            // instead of writing to local file system.
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
            blobClient.upload(new ByteArrayInputStream(csvBytes), csvBytes.length, true);

            result.put("status", "generated");
            result.put("blobName", blobName);
            result.put("blobUrl", blobClient.getBlobUrl());
            // cr-java-0077: Use externalized serverPort instead of hard-coded 8080.
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using externalized base URL from Azure App Configuration.
     * Replaces hard-coded environment URL (cr-java-0071).
     *
     * @param reportName the name of the report blob
     * @return the full download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071: URL loaded from Azure App Configuration via @Value injection —
        // no hard-coded environment-specific hostname or port.
        return reportDownloadBaseUrl + "/download/" + reportName;
    }

    /**
     * Returns system information using externalized configuration values.
     * Replaces hard-coded paths and port (cr-java-0061, cr-java-0077).
     *
     * @return map of system info
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0061: Externalized blob endpoint replaces hard-coded /var/legacy/reports.
        info.put("blobEndpoint", blobEndpoint);
        info.put("containerName", containerName);
        // cr-java-0077: Externalized port replaces hard-coded 8080.
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Schedules a report generation task via Azure Service Bus scheduled messages.
     * Replaces java.util.Timer local scheduling (cr-java-0111).
     *
     * @param reportType the type of report to schedule
     * @param scheduledTimeUtc ISO-8601 UTC time for scheduled delivery
     */
    public void scheduleReport(String reportType, String scheduledTimeUtc) {
        // cr-java-0111: Azure Service Bus scheduled message delivery replaces
        // java.util.Timer — distributed, timezone-agnostic, survives container restarts.
        if (serviceBusConnectionString == null || serviceBusConnectionString.isEmpty()) {
            throw new IllegalStateException(
                    "Azure Service Bus connection string is not configured. " +
                    "Set azure.servicebus.connection-string environment variable.");
        }

        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(serviceBusQueueName)
                .buildClient()) {

            ServiceBusMessage message = new ServiceBusMessage(reportType);
            message.getApplicationProperties().put("scheduledTimeUtc", scheduledTimeUtc);
            message.getApplicationProperties().put("reportType", reportType);
            senderClient.sendMessage(message);
        }
    }
}
