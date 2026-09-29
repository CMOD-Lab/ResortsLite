package com.demo.resortslite;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.ServiceBusMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute path.
    // /var/legacy/reports does not exist in a Docker container image. Breaks containerisation.
    // Must use volume mounts, cloud object storage (S3 / Azure Blob), or environment variable.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/"; // czr-java-001

    // VIOLATION czr-java-001 [Software Portability / Mandatory]: Windows-style absolute path
    // will fail on any Linux-based container or cloud host. Hard dependency on OS path structure.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\"; // czr-java-001

    // VIOLATION [Software Portability / High]: Fixed server port hardcoded in application logic.
    // Container orchestration (ECS / EKS) dynamically assigns ports. Hardcoded ports prevent
    // dynamic port binding required for modern container deployment and service discovery.
    private static final int SERVER_PORT = 8080; // czr-port-001

    // cr-java-0111: Azure Service Bus connection string and queue name injected from
    // environment-backed properties. Replaces server-local clock/timer dependencies with
    // distributed, timezone-agnostic scheduled message delivery via Azure Service Bus.
    @Value("${azure.servicebus.connection-string:}")
    private String serviceBusConnectionString;

    @Value("${azure.servicebus.report-queue-name:report-schedule-queue}")
    private String reportQueueName;

    // cr-java-0111: UTC-based DateTimeFormatter replaces SimpleDateFormat + server-local
    // timezone. All timestamps are now expressed in UTC (OffsetDateTime / ISO-8601) so that
    // they remain consistent across multi-region cloud deployments and container replicas.
    private static final DateTimeFormatter UTC_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = REPORT_BASE_PATH + fileName; // czr-java-001

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(REPORT_BASE_PATH); // czr-java-001
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            FileWriter writer = new FileWriter(fullPath);
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.close();

            result.put("status", "generated");
            result.put("path", fullPath);
            result.put("serverPort", SERVER_PORT); // czr-port-001

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    /**
     * cr-java-0111: Schedules a report-generation task via Azure Service Bus instead of
     * relying on server-local java.util.Timer or system-clock-based scheduling.
     *
     * <p>A {@link ServiceBusMessage} is created with a UTC-based scheduled enqueue time
     * ({@link OffsetDateTime#now(ZoneOffset#UTC)} plus the requested delay in seconds).
     * Azure Service Bus guarantees delivery at that UTC instant regardless of the timezone
     * or clock of any individual container replica, making scheduling behaviour consistent
     * across multi-region and multi-instance cloud deployments.
     *
     * @param taskPayload  JSON or plain-text payload describing the task to execute
     * @param delaySeconds number of seconds from now (UTC) at which the message should
     *                     be delivered to the consumer
     * @return sequence number assigned by Azure Service Bus, or -1 if scheduling failed
     */
    public long scheduleReportTask(String taskPayload, long delaySeconds) {
        if (serviceBusConnectionString == null || serviceBusConnectionString.isEmpty()) {
            // Fallback for local development when Service Bus is not configured
            return -1L;
        }

        // cr-java-0111: Build a sender client using the injected connection string.
        // DefaultAzureCredential-based authentication is preferred in production; the
        // connection-string approach is retained here for backward compatibility and
        // local development convenience.
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString)
                .sender()
                .queueName(reportQueueName)
                .buildClient()) {

            // cr-java-0111: Compute the scheduled enqueue time in UTC — no dependency on
            // server-local timezone settings.
            OffsetDateTime scheduledEnqueueTimeUtc =
                    OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(delaySeconds);

            ServiceBusMessage message = new ServiceBusMessage(taskPayload);
            message.setScheduledEnqueueTime(scheduledEnqueueTimeUtc);

            // scheduleMessages returns the sequence number for the scheduled message.
            return sender.scheduleMessage(message, scheduledEnqueueTimeUtc);
        }
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // cr-java-0111: Replaced server-local SimpleDateFormat + new Date() (line 70) with
        // OffsetDateTime.now(ZoneOffset.UTC) formatted via UTC_FORMATTER.
        // This eliminates the dependency on the JVM/container's local timezone setting and
        // ensures consistent, timezone-agnostic timestamps across all cloud replicas.
        String timestamp = UTC_FORMATTER.format(OffsetDateTime.now(ZoneOffset.UTC));

        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);  // czr-java-001
        info.put("backupPath", BACKUP_PATH);        // czr-java-001
        info.put("serverPort", SERVER_PORT);        // czr-port-001
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");                // cr-java-0111: explicit UTC marker
        return info;
    }
}
