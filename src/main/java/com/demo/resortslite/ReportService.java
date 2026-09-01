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
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-ready service for resort report generation and scheduling.
 *
 * <p>cr-java-0111 REMEDIATION: Clock/Time Dependencies
 * The original implementation used {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())}
 * (line 70 in original source) which relies on the server-local timezone setting.
 * In cloud deployments across multiple regions or containers, timezone inconsistencies
 * cause scheduling failures and time-related logic errors.
 *
 * <p>Fixes applied:
 * <ul>
 *   <li>Replaced {@code java.util.Date} / {@code SimpleDateFormat} with {@code OffsetDateTime.now(ZoneOffset.UTC)}
 *       and {@code DateTimeFormatter.ISO_OFFSET_DATE_TIME} — always UTC, timezone-agnostic.</li>
 *   <li>Replaced any {@code java.util.Timer}-based scheduling with Azure Service Bus
 *       scheduled message delivery, which provides distributed, timezone-agnostic task
 *       execution across all container instances without relying on server-local clocks.</li>
 * </ul>
 */
@Service
public class ReportService {

    // Azure Blob Storage connection string injected from environment variable / application config.
    // Replaces hard-coded absolute file paths (/var/legacy/reports/ and C:\ResortBackups\nightly\)
    // that were incompatible with cloud / container environments (cr-java-0062).
    @Value("${azure.storage.connection-string:${AZURE_STORAGE_CONNECTION_STRING:}}")
    private String storageConnectionString;

    // Container name for reports, externalised via environment variable.
    @Value("${azure.storage.reports-container:${AZURE_STORAGE_REPORTS_CONTAINER:resort-reports}}")
    private String reportsContainerName;

    // Server port externalised via environment variable (replaces hardcoded SERVER_PORT = 8080).
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0071 REMEDIATION: Hard-coded report download base URL
    // "http://reports.resorts-internal.com:8080/download/" (line 66 in original source)
    // replaced with a value injected from Azure App Configuration / environment variable
    // APP_REPORTS_BASE_URL. Configure this property in Azure App Service application settings
    // or Azure App Configuration to make the endpoint environment-agnostic across
    // dev / staging / production deployments without any code changes.
    @Value("${app.reports.base-url:${APP_REPORTS_BASE_URL:https://reports.resorts-internal.com/download/}}")
    private String reportsBaseUrl;

    // cr-java-0111 REMEDIATION: Azure Service Bus connection string for scheduled message delivery.
    // Replaces java.util.Timer / server-local scheduling with Azure Service Bus scheduled messages,
    // providing distributed, timezone-agnostic task execution across all container instances.
    // Set AZURE_SERVICE_BUS_CONNECTION_STRING as an Azure App Service application setting.
    @Value("${azure.servicebus.connection-string:${AZURE_SERVICE_BUS_CONNECTION_STRING:}}")
    private String serviceBusConnectionString;

    // cr-java-0111 REMEDIATION: Azure Service Bus queue name for report scheduling tasks.
    // Set AZURE_SERVICE_BUS_REPORT_QUEUE as an Azure App Service application setting.
    @Value("${azure.servicebus.report-queue:${AZURE_SERVICE_BUS_REPORT_QUEUE:resort-report-queue}}")
    private String reportQueueName;

    /**
     * Generates a monthly report CSV and uploads it to Azure Blob Storage.
     *
     * <p>Remediation for cr-java-0062 (Local File System Write Operations):
     * The original implementation used {@code new File(REPORT_BASE_PATH)} (line 42) and
     * {@code new FileWriter(fullPath)} to persist report data on the local file system.
     * In cloud/containerised environments the local file system is ephemeral — data written
     * locally is lost on container restart or scale-out events.  All write operations have
     * been migrated to Azure Blob Storage so that report data is durable and available
     * across container restarts and horizontal scaling.</p>
     *
     * @param month the month for which the report is generated
     * @param year  the year for which the report is generated
     * @return a map containing the operation status and the blob URL
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String blobName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency.
            // cr-java-0062 fix: replaced File/FileWriter local writes with in-memory byte array
            // that is streamed directly to Azure Blob Storage.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);

            // cr-java-0062 fix (line 42): replaced `new File(REPORT_BASE_PATH)` / `reportDir.mkdirs()`
            // and `new FileWriter(fullPath)` with Azure Blob Storage upload.
            // Data is now persisted in Azure Blob Storage, ensuring durability across
            // container restarts and scaling events.
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .connectionString(storageConnectionString)
                    .buildClient();

            BlobContainerClient containerClient = blobServiceClient
                    .getBlobContainerClient(reportsContainerName);

            // Create the container if it does not already exist.
            if (!containerClient.exists()) {
                containerClient.create();
            }

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
     * Schedules a monthly report generation task via Azure Service Bus scheduled message delivery.
     *
     * <p>cr-java-0111 REMEDIATION: Clock/Time Dependencies — replaces {@code java.util.Timer}
     * and server-local scheduling with Azure Service Bus scheduled messages.  The scheduled
     * enqueue time is expressed in UTC ({@code OffsetDateTime.now(ZoneOffset.UTC)}) so the
     * schedule is timezone-agnostic and consistent across all container instances and regions.
     * Azure Service Bus guarantees delivery at the specified UTC time regardless of the
     * server-local timezone of any individual container.</p>
     *
     * @param month          the month for which the report should be generated
     * @param year           the year for which the report should be generated
     * @param delayInSeconds number of seconds from now (UTC) at which the message should be delivered
     * @return a map containing the scheduling status and the scheduled enqueue time in UTC ISO-8601
     */
    public Map<String, Object> scheduleReportGeneration(String month, String year, long delayInSeconds) {
        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0111 REMEDIATION: Use UTC OffsetDateTime instead of server-local Date/Calendar.
            // OffsetDateTime.now(ZoneOffset.UTC) is timezone-agnostic and consistent across
            // all container instances regardless of the host OS timezone setting.
            OffsetDateTime scheduledEnqueueTime = OffsetDateTime.now(ZoneOffset.UTC)
                    .plusSeconds(delayInSeconds);

            String messageBody = String.format(
                    "{\"action\":\"generateMonthlyReport\",\"month\":\"%s\",\"year\":\"%s\"}",
                    month, year);

            ServiceBusMessage scheduledMessage = new ServiceBusMessage(messageBody);
            scheduledMessage.setScheduledEnqueueTime(scheduledEnqueueTime);

            // cr-java-0111 REMEDIATION: Azure Service Bus SenderClient delivers the message
            // at the specified UTC time, decoupling scheduling from any single container's
            // local clock or timezone. All instances consume from the same queue, enabling
            // distributed, horizontally-scalable task execution.
            try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                    .connectionString(serviceBusConnectionString)
                    .sender()
                    .queueName(reportQueueName)
                    .buildClient()) {

                long sequenceNumber = senderClient.scheduleMessage(
                        scheduledMessage, scheduledEnqueueTime);

                result.put("status", "scheduled");
                result.put("sequenceNumber", sequenceNumber);
                result.put("scheduledEnqueueTimeUtc",
                        scheduledEnqueueTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                result.put("queueName", reportQueueName);
            }

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the externalized base URL from Azure App Configuration.
     *
     * <p>Remediation for cr-java-0071 (Hard-coded Environment URLs):
     * The original implementation returned a hard-coded URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} (line 66 in original source).
     * The base URL is now injected via {@code @Value} from the {@code app.reports.base-url}
     * property, which is resolved from Azure App Configuration or the {@code APP_REPORTS_BASE_URL}
     * environment variable, enabling environment-agnostic deployments.</p>
     *
     * @param reportName the name of the report to download
     * @return the download URL for the specified report, constructed from the externalized base URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 REMEDIATION: replaced hard-coded
        // "http://reports.resorts-internal.com:8080/download/" with the externalized
        // `reportsBaseUrl` field injected via @Value from Azure App Configuration /
        // APP_REPORTS_BASE_URL environment variable.
        return reportsBaseUrl + reportName;
    }

    /**
     * Returns system information including Azure Blob Storage container details.
     *
     * <p>cr-java-0111 REMEDIATION: Clock/Time Dependencies — replaced
     * {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())} (line 70 in
     * original source) with {@code OffsetDateTime.now(ZoneOffset.UTC)} formatted via
     * {@code DateTimeFormatter.ISO_OFFSET_DATE_TIME}.  This produces a UTC ISO-8601 timestamp
     * that is timezone-agnostic and consistent across all container instances and cloud regions,
     * eliminating server-local timezone dependencies that cause time-related logic errors in
     * distributed cloud deployments.</p>
     *
     * <p>Replaces the previous implementation that exposed local file system paths
     * {@code /var/legacy/reports/} and {@code C:\ResortBackups\nightly\} (cr-java-0062).</p>
     *
     * @return a map containing storage container info and the current UTC timestamp
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 REMEDIATION (line 70 in original source):
        // Replaced server-local `new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())`
        // with UTC-based `OffsetDateTime.now(ZoneOffset.UTC)` formatted as ISO-8601.
        // This is timezone-agnostic and consistent across all container instances and regions.
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> info = new HashMap<>();
        // cr-java-0062 fix: replaced hard-coded REPORT_BASE_PATH (/var/legacy/reports/) and
        // BACKUP_PATH (C:\ResortBackups\nightly\) with Azure Blob Storage container references.
        info.put("reportsContainer", reportsContainerName);
        info.put("storageType", "Azure Blob Storage");
        info.put("serverPort", serverPort);
        // cr-java-0111 fix: UTC ISO-8601 timestamp — no server-local timezone dependency.
        info.put("generatedAtUtc", timestamp);
        info.put("schedulingBackend", "Azure Service Bus Scheduled Messages");
        info.put("reportQueue", reportQueueName);
        return info;
    }
}
