package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private static final String BLOB_ENDPOINT_ENV = "AZURE_STORAGE_BLOB_ENDPOINT";
    private static final String BLOB_CONTAINER_ENV = "AZURE_REPORTS_CONTAINER";
    private static final String REPORT_DOWNLOAD_URL_KEY_ENV = "AZURE_APP_CONFIG_REPORT_DOWNLOAD_URL_KEY";
    private static final String REPORT_DOWNLOAD_URL_DEFAULT_KEY = "report.download.base-url";
    private static final String SERVER_PORT_KEY_ENV = "AZURE_APP_CONFIG_SERVER_PORT_KEY";
    private static final String SERVER_PORT_DEFAULT_KEY = "server.port";
    private static final String SERVICE_BUS_NAMESPACE_ENV = "AZURE_SERVICE_BUS_NAMESPACE";
    private static final String SERVICE_BUS_QUEUE_ENV = "AZURE_SERVICE_BUS_QUEUE_NAME";
    private static final String KEY_VAULT_URI_ENV = "AZURE_KEY_VAULT_URI";
    private static final String APP_CONFIG_PORT_ENV = "SERVER_PORT";
    private static final String DEFAULT_REPORTS_CONTAINER = "reports";
    private static final String DEFAULT_SERVER_PORT = "8080";
    private static final String DEFAULT_REPORT_DOWNLOAD_URL = "https://reports.resorts-internal.com/download/";

    private final Clock clock = Clock.systemUTC();

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

        Map<String, Object> result = new HashMap<>();

        try {
            BlobClient blobClient = getReportsContainerClient().getBlobClient(fileName);
            blobClient.upload(new ByteArrayInputStream(reportContent.getBytes(StandardCharsets.UTF_8)),
                    reportContent.getBytes(StandardCharsets.UTF_8).length, true);

            result.put("status", "generated");
            result.put("path", blobClient.getBlobUrl());
            result.put("serverPort", Integer.parseInt(resolveServerPort()));

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        String baseUrl = resolveAppConfigurationValue(REPORT_DOWNLOAD_URL_KEY_ENV,
                REPORT_DOWNLOAD_URL_DEFAULT_KEY,
                DEFAULT_REPORT_DOWNLOAD_URL);
        return appendPath(baseUrl, reportName);
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", getReportsContainerClient().getBlobContainerUrl());
        info.put("backupPath", getReportsContainerClient().getBlobContainerUrl());
        info.put("serverPort", Integer.parseInt(resolveServerPort()));
        info.put("generatedAt", timestamp);
        return info;
    }

    public void scheduleReportGeneration(String reportName) {
        String namespace = requireEnv(SERVICE_BUS_NAMESPACE_ENV);
        String queueName = requireEnv(SERVICE_BUS_QUEUE_ENV);

        ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                .credential(namespace, new DefaultAzureCredentialBuilder().build())
                .sender()
                .queueName(queueName)
                .buildClient();
        try {
            ServiceBusMessage message = new ServiceBusMessage(reportName)
                    .setScheduledEnqueueTime(OffsetDateTime.now(clock).plus(1, ChronoUnit.HOURS));
            senderClient.scheduleMessage(message, message.getScheduledEnqueueTime());
        } finally {
            senderClient.close();
        }
    }

    private BlobContainerClient getReportsContainerClient() {
        String endpoint = requireEnv(BLOB_ENDPOINT_ENV);
        String containerName = envOrDefault(BLOB_CONTAINER_ENV, DEFAULT_REPORTS_CONTAINER);
        BlobContainerClient client = new BlobContainerClientBuilder()
                .endpoint(appendPath(endpoint, containerName))
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        if (!client.exists()) {
            client.create();
        }
        return client;
    }

    private String resolveServerPort() {
        return System.getenv().getOrDefault(APP_CONFIG_PORT_ENV,
                resolveAppConfigurationValue(SERVER_PORT_KEY_ENV, SERVER_PORT_DEFAULT_KEY, DEFAULT_SERVER_PORT));
    }

    private String resolveAppConfigurationValue(String keyEnvName, String defaultKey, String fallbackValue) {
        String keyVaultUri = System.getenv(KEY_VAULT_URI_ENV);
        if (keyVaultUri != null && !keyVaultUri.trim().isEmpty()) {
            try {
                SecretClient secretClient = new SecretClientBuilder()
                        .vaultUrl(keyVaultUri)
                        .credential(new DefaultAzureCredentialBuilder().build())
                        .buildClient();
                String key = envOrDefault(keyEnvName, defaultKey);
                return secretClient.getSecret(key).getValue();
            } catch (Exception ignored) {
                return fallbackValue;
            }
        }
        return fallbackValue;
    }

    private String appendPath(String base, String path) {
        String normalizedBase = base.endsWith("/") ? base : base + "/";
        return normalizedBase + path;
    }

    private String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    private String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }
}
