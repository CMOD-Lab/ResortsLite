package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    @Value("${azure.storage.blob.endpoint:}")
    private String blobEndpoint;

    @Value("${azure.storage.blob.container-name:reports}")
    private String blobContainerName;

    @Value("${app.reports.backup-prefix:nightly/}")
    private String backupPrefix;

    @Value("${app.server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    @Value("${app.report.download-base-url:https://reports.resorts-internal/download/}")
    private String reportDownloadBaseUrl;

    @Value("${azure.servicebus.fully-qualified-namespace:}")
    private String serviceBusNamespace;

    @Value("${azure.servicebus.queue-name:report-jobs}")
    private String serviceBusQueueName;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

        Map<String, Object> result = new HashMap<>();

        try {
            BlobClient reportBlobClient = getContainerClient().getBlobClient(fileName);
            byte[] content = csvContent.getBytes(StandardCharsets.UTF_8);
            reportBlobClient.upload(new ByteArrayInputStream(content), content.length, true);

            result.put("status", "generated");
            result.put("path", reportBlobClient.getBlobUrl());
            result.put("backupPath", buildBackupLocation(fileName));
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        String normalizedBaseUrl = reportDownloadBaseUrl.endsWith("/")
                ? reportDownloadBaseUrl
                : reportDownloadBaseUrl + "/";
        return normalizedBaseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", blobEndpoint + "/" + blobContainerName);
        info.put("backupPath", buildBackupLocation(""));
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    public void scheduleReportGeneration(String month, String year, OffsetDateTime scheduleTimeUtc) {
        if (serviceBusNamespace == null || serviceBusNamespace.trim().isEmpty()) {
            return;
        }

        ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .credential(serviceBusNamespace, new DefaultAzureCredentialBuilder().build())
                .sender()
                .queueName(serviceBusQueueName)
                .buildClient();

        try {
            ServiceBusMessage message = new ServiceBusMessage(month + ":" + year)
                    .setScheduledEnqueueTime(scheduleTimeUtc.withOffsetSameInstant(ZoneOffset.UTC));
            senderClient.sendMessage(message);
        } finally {
            senderClient.close();
        }
    }

    private BlobContainerClient getContainerClient() {
        return new BlobContainerClientBuilder()
                .endpoint(blobEndpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .containerName(blobContainerName)
                .buildClient();
    }

    private String buildBackupLocation(String fileName) {
        String normalizedPrefix = backupPrefix.endsWith("/") ? backupPrefix : backupPrefix + "/";
        return blobEndpoint + "/" + blobContainerName + "/" + normalizedPrefix + fileName;
    }
}
