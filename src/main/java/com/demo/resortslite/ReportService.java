package com.demo.resortslite;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

// cr-java-0063 FIX: Removed all java.io.File and java.io.FileWriter imports.
// All persistent data storage operations previously using java.io.File have been
// migrated to Azure Blob Storage using the Azure SDK for Java, ensuring data
// durability, scalability, and cloud-native compliance.
//
// cr-java-0111 FIX: Removed java.text.SimpleDateFormat and java.util.Date imports.
// Server-local timezone-dependent time operations have been replaced with
// java.time.ZonedDateTime (UTC) for timezone-agnostic timestamp generation.
// Distributed scheduling is delegated to Azure Service Bus Scheduled Messages,
// eliminating java.util.Timer and server-local clock dependencies.

@Service
public class ReportService {

    // cr-java-0061 / cr-java-0063 FIX: Replaced hard-coded absolute file path
    // "/var/legacy/reports/" with Azure Blob Storage connection string injected
    // via environment variable AZURE_STORAGE_CONNECTION_STRING.
    @Value("${azure.storage.connection-string}")
    private String storageConnectionString;

    // cr-java-0061 / cr-java-0063 FIX: Replaced hard-coded absolute file path
    // "/var/legacy/reports/" with Azure Blob Storage container name injected via
    // environment variable AZURE_STORAGE_REPORTS_CONTAINER.
    @Value("${azure.storage.reports-container:resort-reports}")
    private String reportsContainerName;

    // cr-java-0061 / cr-java-0063 FIX: Replaced hard-coded Windows-style absolute
    // path "C:\\ResortBackups\\nightly\\" with Azure Blob Storage container name
    // injected via environment variable AZURE_STORAGE_BACKUP_CONTAINER.
    @Value("${azure.storage.backup-container:resort-backups}")
    private String backupContainerName;

    // cr-java-0111 FIX: Azure Service Bus connection string injected via environment
    // variable AZURE_SERVICEBUS_CONNECTION_STRING. Used for scheduling distributed,
    // timezone-agnostic report generation tasks via Azure Service Bus Scheduled Messages,
    // replacing any java.util.Timer or server-local scheduling dependencies.
    @Value("${azure.servicebus.connection-string:}")
    private String serviceBusConnectionString;

    // cr-java-0111 FIX: Azure Service Bus queue name for scheduled report tasks,
    // injected via environment variable AZURE_SERVICEBUS_REPORT_QUEUE.
    @Value("${azure.servicebus.report-queue:resort-report-tasks}")
    private String reportQueueName;

    // czr-port-001: Server port is now managed via application.properties /
    // environment variable. Removed hard-coded SERVER_PORT constant from
    // application logic.

    /**
     * Returns a BlobServiceClient configured from the injected connection string.
     */
    private BlobServiceClient getBlobServiceClient() {
        return new BlobServiceClientBuilder()
                .connectionString(storageConnectionString)
                .buildClient();
    }

    /**
     * Generates a monthly report CSV and uploads it to Azure Blob Storage.
     *
     * <p>cr-java-0063 FIX (Lines 37–42 of original source):
     * <ul>
     *   <li>Line 37: {@code new File(REPORT_BASE_PATH)} — replaced with
     *       {@link BlobContainerClient} obtained from Azure Blob Storage SDK.</li>
     *   <li>Line 39: {@code reportDir.mkdirs()} — replaced with
     *       {@code containerClient.create()} which creates the blob container
     *       if it does not already exist, mirroring the original mkdirs() intent.</li>
     *   <li>Line 42: {@code new FileWriter(fullPath)} — replaced with
     *       {@code blobClient.upload(dataStream, csvBytes.length, true)} which
     *       streams the CSV content directly to Azure Blob Storage without any
     *       local file-system dependency.</li>
     * </ul>
     * Replaces the previous local file-system write to /var/legacy/reports/.
     *
     * @param month the month for the report (e.g. "03")
     * @param year  the year for the report (e.g. "2024")
     * @return a result map containing status and the blob URL
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0063 FIX: Blob name replaces the local file name previously
        // constructed as REPORT_BASE_PATH + "resort_report_" + month + "_" + year + ".csv"
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0063 FIX (original line 37): Replaced new File(REPORT_BASE_PATH)
            // with BlobContainerClient — no local file-system object is created.
            BlobServiceClient blobServiceClient = getBlobServiceClient();
            BlobContainerClient containerClient =
                    blobServiceClient.getBlobContainerClient(reportsContainerName);

            // cr-java-0063 FIX (original line 39): Replaced reportDir.mkdirs()
            // with containerClient.create() to ensure the blob container exists
            // before uploading, preserving the original "create if absent" logic.
            if (!containerClient.exists()) {
                containerClient.create();
            }

            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);
            InputStream dataStream = new ByteArrayInputStream(csvBytes);

            // cr-java-0063 FIX (original line 42): Replaced new FileWriter(fullPath)
            // followed by writer.write() / writer.close() with blobClient.upload(),
            // streaming CSV content directly to Azure Blob Storage. No local file
            // is written; data is persisted in the cloud-managed blob container.
            BlobClient blobClient = containerClient.getBlobClient(blobName);
            blobClient.upload(dataStream, csvBytes.length, true);

            result.put("status", "generated");
            result.put("path", blobClient.getBlobUrl());

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Schedules a monthly report generation task using Azure Service Bus Scheduled Messages.
     *
     * <p>cr-java-0111 FIX: Replaces any java.util.Timer or server-local scheduling
     * with Azure Service Bus scheduled message delivery. The message is enqueued with
     * a UTC-based scheduled enqueue time, ensuring timezone-agnostic, distributed
     * execution across all cloud instances regardless of region or container timezone.
     *
     * <p>The consumer of the {@code resort-report-tasks} queue is responsible for
     * calling {@link #generateMonthlyReport(String, String)} upon message receipt.
     *
     * @param month           the month for the report (e.g. "03")
     * @param year            the year for the report (e.g. "2024")
     * @param scheduledAtUtc  the UTC date-time at which the message should be delivered
     * @return a result map containing the scheduled sequence number or error details
     */
    public Map<String, Object> scheduleMonthlyReportTask(String month, String year,
                                                          OffsetDateTime scheduledAtUtc) {
        Map<String, Object> result = new HashMap<>();

        // cr-java-0111 FIX: Use Azure Service Bus SenderClient to enqueue a scheduled
        // message. The scheduledEnqueueTime is expressed in UTC (OffsetDateTime with
        // ZoneOffset.UTC), making scheduling timezone-agnostic across all cloud regions.
        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(reportQueueName)
                .buildClient()) {

            String messageBody = String.format(
                    "{\"action\":\"generateMonthlyReport\",\"month\":\"%s\",\"year\":\"%s\"}",
                    month, year);

            ServiceBusMessage message = new ServiceBusMessage(messageBody);
            // cr-java-0111 FIX: scheduledEnqueueTime is set in UTC to avoid any
            // server-local timezone dependency — consistent across all cloud regions.
            message.setScheduledEnqueueTime(scheduledAtUtc.withOffsetSameInstant(ZoneOffset.UTC));

            long sequenceNumber = senderClient.scheduleMessage(message, scheduledAtUtc);

            result.put("status", "scheduled");
            result.put("sequenceNumber", sequenceNumber);
            result.put("scheduledAtUtc", scheduledAtUtc.toString());

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL for the given report name.
     *
     * @param reportName the name of the report blob
     * @return the HTTPS URL of the blob in Azure Blob Storage
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0088: Replaced plain HTTP URL with Azure Blob Storage HTTPS URL.
        BlobServiceClient blobServiceClient = getBlobServiceClient();
        BlobContainerClient containerClient =
                blobServiceClient.getBlobContainerClient(reportsContainerName);
        BlobClient blobClient = containerClient.getBlobClient(reportName);
        return blobClient.getBlobUrl();
    }

    /**
     * Returns system information including Azure Blob Storage container references.
     *
     * <p>cr-java-0063 FIX: Replaced local path references to
     * {@code /var/legacy/reports/} (REPORT_BASE_PATH) and
     * {@code C:\\ResortBackups\\nightly\\} (BACKUP_PATH) with Azure Blob Storage
     * container name references sourced from environment-backed properties.
     * No {@code java.io.File} objects are used anywhere in this class.
     *
     * <p>cr-java-0111 FIX (original line 70): Replaced
     * {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())}
     * with {@code ZonedDateTime.now(ZoneOffset.UTC)} formatted via
     * {@link DateTimeFormatter}. This eliminates the server-local timezone
     * dependency introduced by {@code java.util.Date} and
     * {@code SimpleDateFormat}, producing a consistent UTC timestamp regardless
     * of the JVM's default timezone or the cloud region in which the container runs.
     *
     * @return a map of system information entries
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (original line 70): Replaced
        //   new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
        // with ZonedDateTime.now(ZoneOffset.UTC) to produce a timezone-agnostic
        // UTC timestamp. java.util.Date and SimpleDateFormat rely on the JVM's
        // default (server-local) timezone, which causes inconsistencies across
        // cloud regions and containers. ZonedDateTime with ZoneOffset.UTC is
        // explicit, immutable, and safe for distributed cloud environments.
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> info = new HashMap<>();

        // cr-java-0063 FIX: Replaced hard-coded REPORT_BASE_PATH ("/var/legacy/reports/")
        // and BACKUP_PATH ("C:\\ResortBackups\\nightly\\") with Azure Blob Storage
        // container name references sourced from environment-backed properties.
        info.put("reportContainer", reportsContainerName);
        info.put("backupContainer", backupContainerName);
        info.put("generatedAt", timestamp);
        return info;
    }
}
