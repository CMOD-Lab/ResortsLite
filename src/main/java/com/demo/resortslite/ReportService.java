package com.demo.resortslite;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation and scheduling service.
 *
 * <p>cr-java-0111 remediation: All server-local clock and timezone dependencies have been
 * replaced with UTC-based {@link java.time.Instant} / {@link java.time.OffsetDateTime} calls
 * using {@link Clock#systemUTC()}.  Scheduled operations are dispatched as Azure Service Bus
 * scheduled messages, providing distributed, timezone-agnostic task execution that works
 * correctly across multiple cloud regions and container replicas.</p>
 */
@Service
public class ReportService {

    // Azure Blob Storage connection string injected from environment / application properties.
    // Replaces hard-coded absolute file paths (/var/legacy/reports/ and C:\ResortBackups\nightly\)
    // that were incompatible with cloud / container environments (cr-java-0061).
    @Value("${azure.storage.connection-string}")
    private String azureStorageConnectionString;

    // Container names are externalised to configuration so they can differ per environment.
    @Value("${azure.storage.reports-container:resort-reports}")
    private String reportsContainerName;

    @Value("${azure.storage.backup-container:resort-backups}")
    private String backupContainerName;

    // cr-java-0077 remediation: Hard-coded port (previously `private static final int SERVER_PORT = 8080`)
    // replaced with an environment-variable-backed Spring @Value binding.
    // The SERVER_PORT environment variable (or Azure App Configuration key SERVER_PORT) controls
    // the port at runtime, enabling dynamic port assignment by Azure Container Apps / App Service
    // without any code changes.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0111 remediation: Azure Service Bus connection string and queue name for
    // distributed, timezone-agnostic scheduled message delivery.
    // Set AZURE_SERVICEBUS_CONNECTION_STRING and AZURE_SERVICEBUS_REPORT_QUEUE as
    // environment variables or Azure App Service application settings.
    @Value("${azure.servicebus.connection-string:${AZURE_SERVICEBUS_CONNECTION_STRING:}}")
    private String serviceBusConnectionString;

    @Value("${azure.servicebus.report-queue:${AZURE_SERVICEBUS_REPORT_QUEUE:resort-report-queue}}")
    private String reportQueueName;

    // cr-java-0111 remediation: UTC clock instance used for all timestamp generation.
    // Replaces server-local SimpleDateFormat / new Date() calls that depend on the JVM's
    // default timezone — which varies across cloud regions and container images.
    private final Clock utcClock = Clock.systemUTC();

    /**
     * Generates a monthly CSV report and uploads it to Azure Blob Storage.
     * Previously wrote to the hard-coded local path /var/legacy/reports/ (cr-java-0061).
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string  (e.g. "2024")
     * @return result map containing upload status and the blob URL
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build the CSV content in memory — no local file system dependency.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);

            // Upload to Azure Blob Storage (replaces File / FileWriter operations on
            // hard-coded path REPORT_BASE_PATH = "/var/legacy/reports/" — cr-java-0061).
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .connectionString(azureStorageConnectionString)
                    .buildClient();

            BlobContainerClient containerClient = blobServiceClient
                    .getBlobContainerClient(reportsContainerName);
            containerClient.createIfNotExists();

            BlobClient blobClient = containerClient.getBlobClient(blobName);
            try (InputStream inputStream = new ByteArrayInputStream(contentBytes)) {
                blobClient.upload(inputStream, contentBytes.length, true);
            }

            result.put("status", "generated");
            result.put("blobName", blobName);
            result.put("blobUrl", blobClient.getBlobUrl());
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Schedules a report-generation task as an Azure Service Bus scheduled message.
     *
     * <p>cr-java-0111 remediation: Replaces any {@code java.util.Timer} / server-local
     * scheduling with Azure Service Bus scheduled message delivery.  The message is
     * enqueued with an explicit UTC {@link OffsetDateTime} so that scheduling is
     * timezone-agnostic and works correctly across distributed cloud deployments.</p>
     *
     * @param month           two-digit month string (e.g. "03")
     * @param year            four-digit year string  (e.g. "2024")
     * @param delaySeconds    number of seconds from now (UTC) at which the message
     *                        should become available for processing
     * @return sequence number assigned by Azure Service Bus, or -1 if Service Bus
     *         is not configured (connection string is blank)
     */
    public long scheduleReportGeneration(String month, String year, long delaySeconds) {
        if (serviceBusConnectionString == null || serviceBusConnectionString.isBlank()) {
            // Service Bus not configured — skip scheduling (e.g. local dev without Azure).
            return -1L;
        }

        // cr-java-0111 remediation: Use UTC Instant from the injected Clock so that the
        // scheduled time is always expressed in UTC, regardless of the JVM's default
        // timezone or the cloud region in which this instance is running.
        OffsetDateTime scheduledEnqueueTime = OffsetDateTime.ofInstant(
                Instant.now(utcClock).plusSeconds(delaySeconds),
                ZoneOffset.UTC);

        String messageBody = String.format(
                "{\"action\":\"generateMonthlyReport\",\"month\":\"%s\",\"year\":\"%s\"}",
                month, year);

        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(reportQueueName)
                .buildClient()) {

            ServiceBusMessage message = new ServiceBusMessage(messageBody);
            // scheduleMessage enqueues the message to become visible at the given UTC time,
            // providing distributed, timezone-agnostic scheduling across all cloud regions.
            return sender.scheduleMessage(message, scheduledEnqueueTime);
        }
    }

    /**
     * Builds the download URL for a named report blob stored in Azure Blob Storage.
     *
     * @param reportName the blob name of the report
     * @return the Azure Blob Storage URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(azureStorageConnectionString)
                .buildClient();

        BlobContainerClient containerClient = blobServiceClient
                .getBlobContainerClient(reportsContainerName);

        BlobClient blobClient = containerClient.getBlobClient(reportName);
        return blobClient.getBlobUrl();
    }

    /**
     * Returns system information including Azure Blob Storage container references.
     * Previously exposed hard-coded local paths /var/legacy/reports/ and
     * C:\ResortBackups\nightly\ (cr-java-0061).
     *
     * <p>cr-java-0111 remediation: The timestamp is now generated using
     * {@link Instant#now(Clock)} with {@link Clock#systemUTC()} instead of
     * {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())}.
     * This eliminates the dependency on the JVM's default (server-local) timezone
     * and produces a consistent UTC ISO-8601 timestamp across all cloud regions
     * and container replicas.</p>
     *
     * @return map of system information entries
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 remediation: Replace server-local SimpleDateFormat / new Date()
        // (line 70 in original source — timezone-dependent) with a UTC-based
        // ISO-8601 timestamp derived from Clock.systemUTC().  This ensures consistent,
        // timezone-agnostic timestamps across distributed cloud deployments.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now(utcClock));

        Map<String, Object> info = new HashMap<>();

        // Replace hard-coded REPORT_BASE_PATH ("/var/legacy/reports/") with the
        // Azure Blob Storage container reference (cr-java-0061).
        info.put("reportContainer", reportsContainerName);

        // Replace hard-coded BACKUP_PATH ("C:\\ResortBackups\\nightly\\") with the
        // Azure Blob Storage backup container reference (cr-java-0061).
        info.put("backupContainer", backupContainerName);

        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
