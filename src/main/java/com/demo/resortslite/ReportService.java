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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Blocker-1/2/3 (cr-java-0061): Hard-coded file paths replaced with Azure App Configuration
    // environment variables. REPORT_BASE_PATH and BACKUP_PATH are no longer hard-coded.
    @Value("${azure.storage.blob.endpoint}")
    private String blobEndpoint;

    @Value("${azure.storage.blob.container-name:reports}")
    private String containerName;

    // Blocker-12 (cr-java-0077): Hard-coded port replaced with environment variable / config.
    @Value("${server.port:${PORT:8080}}")
    private int serverPort;

    // Blocker-11 (cr-java-0071): Hard-coded report download URL replaced with externalized config.
    @Value("${app.report.download.base-url}")
    private String reportDownloadBaseUrl;

    // Blocker-19 (cr-java-0111): Azure Service Bus connection string for scheduled messages.
    @Value("${azure.servicebus.connection-string:}")
    private String serviceBusConnectionString;

    @Value("${azure.servicebus.queue-name:report-schedule-queue}")
    private String serviceBusQueueName;

    /**
     * Builds an Azure Blob Storage client authenticated via DefaultAzureCredential.
     */
    private BlobContainerClient getBlobContainerClient() {
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .endpoint(blobEndpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            containerClient.create();
        }
        return containerClient;
    }

    /**
     * Generates a monthly report and uploads it to Azure Blob Storage.
     * Blocker-4 (cr-java-0062): Local file write replaced with Azure Blob Storage upload.
     * Blocker-5/6/7 (cr-java-0063): java.io.File usage replaced with Azure Blob Storage SDK.
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.flush();
            writer.close();

            byte[] csvBytes = baos.toByteArray();

            // Upload to Azure Blob Storage
            BlobContainerClient containerClient = getBlobContainerClient();
            BlobClient blobClient = containerClient.getBlobClient(blobName);
            blobClient.upload(new ByteArrayInputStream(csvBytes), csvBytes.length, true);

            result.put("status", "generated");
            result.put("blobName", blobName);
            result.put("containerName", containerName);
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using externalized configuration.
     * Blocker-11 (cr-java-0071): Hard-coded URL replaced with Azure App Configuration value.
     */
    public String buildReportDownloadUrl(String reportName) {
        // URL base is loaded from Azure App Configuration via @Value — no hard-coded host/port
        return reportDownloadBaseUrl + "/download/" + reportName;
    }

    /**
     * Returns system information using externalized configuration values.
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("blobEndpoint", blobEndpoint);
        info.put("containerName", containerName);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Schedules a report generation task via Azure Service Bus scheduled message.
     * Blocker-19 (cr-java-0111): java.util.Timer replaced with Azure Service Bus scheduled messages
     * for distributed, timezone-agnostic task execution.
     */
    public Map<String, Object> scheduleReportGeneration(String month, String year, long delaySeconds) {
        Map<String, Object> result = new HashMap<>();
        try {
            ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                    .connectionString(serviceBusConnectionString)
                    .sender()
                    .queueName(serviceBusQueueName)
                    .buildClient();

            String messageBody = "{\"action\":\"generateReport\",\"month\":\"" + month
                    + "\",\"year\":\"" + year + "\"}";
            ServiceBusMessage message = new ServiceBusMessage(messageBody);
            // Schedule the message to be enqueued after the specified delay
            java.time.OffsetDateTime scheduledTime =
                    java.time.OffsetDateTime.now().plusSeconds(delaySeconds);
            long sequenceNumber = senderClient.scheduleMessage(message, scheduledTime);
            senderClient.close();

            result.put("status", "scheduled");
            result.put("sequenceNumber", sequenceNumber);
            result.put("scheduledTime", scheduledTime.toString());
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }
}
