package com.demo.resortslite;

import com.azure.core.credential.TokenCredential;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private static final String DEFAULT_REPORT_CONTAINER = "reports";
    private static final String DEFAULT_BACKUP_CONTAINER = "report-backups";
    private static final int DEFAULT_SERVER_PORT = 8080;
    private static final String DEFAULT_REPORT_DOWNLOAD_PATH = "/download/";
    private static final String DEFAULT_REPORT_DOWNLOAD_BASE_URL = "https://reports.resorts-internal.com";

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String csvContent = buildMonthlyReportContent();
        String blobUrl = uploadReportToBlobStorage(fileName, csvContent);

        Map<String, Object> result = new HashMap<>();

        try {
            result.put("status", "generated");
            result.put("path", blobUrl);
            result.put("serverPort", getServerPort());
        } catch (RuntimeException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        return getConfigValue("reports.download.base-url", DEFAULT_REPORT_DOWNLOAD_BASE_URL)
                + getConfigValue("reports.download.path", DEFAULT_REPORT_DOWNLOAD_PATH)
                + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", getBlobContainerName());
        info.put("backupPath", getBackupContainerName());
        info.put("serverPort", getServerPort());
        info.put("generatedAt", timestamp);
        return info;
    }

    public void scheduleReportGeneration(String reportName) {
        String connectionString = getRequiredEnvironmentVariable("AZURE_SERVICEBUS_CONNECTION_STRING");
        String queueName = getConfigValue("servicebus.report.queue-name", "report-scheduling");
        OffsetDateTime scheduledTime = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5);

        try (ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .queueName(queueName)
                .buildClient()) {
            senderClient.scheduleMessage(
                    new com.azure.messaging.servicebus.ServiceBusMessage(reportName),
                    scheduledTime);
        }
    }

    private String buildMonthlyReportContent() {
        return "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";
    }

    private String uploadReportToBlobStorage(String fileName, String content) {
        BlobContainerClient containerClient = new BlobContainerClientBuilder()
                .connectionString(getRequiredEnvironmentVariable("AZURE_STORAGE_CONNECTION_STRING"))
                .containerName(getBlobContainerName())
                .buildClient();

        if (!containerClient.exists()) {
            containerClient.create();
        }

        BlobClient blobClient = containerClient.getBlobClient(fileName);
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        blobClient.upload(new ByteArrayInputStream(payload), payload.length, true);
        return blobClient.getBlobUrl();
    }

    private int getServerPort() {
        String configuredPort = getConfigValue("service.report.port", String.valueOf(DEFAULT_SERVER_PORT));
        return Integer.parseInt(configuredPort);
    }

    private String getBlobContainerName() {
        return getConfigValue("storage.report.container", DEFAULT_REPORT_CONTAINER);
    }

    private String getBackupContainerName() {
        return getConfigValue("storage.backup.container", DEFAULT_BACKUP_CONTAINER);
    }

    private String getConfigValue(String key, String defaultValue) {
        String envKey = key.toUpperCase().replace('.', '_').replace('-', '_');
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue;
        }

        String connectionString = System.getenv("AZURE_APP_CONFIGURATION_CONNECTION_STRING");
        if (connectionString == null || connectionString.trim().isEmpty()) {
            return defaultValue;
        }

        try {
            ConfigurationClient client = new ConfigurationClientBuilder()
                    .connectionString(connectionString)
                    .buildClient();
            String value = client.getConfigurationSetting(key, null).getValue();
            return value != null && !value.trim().isEmpty() ? value : defaultValue;
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private String getRequiredEnvironmentVariable(String key) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return value;
    }
}
