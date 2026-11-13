package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.annotation.PostConstruct;

/**
 * Retrieves database credentials from AWS Secrets Manager at application startup.
 *
 * <p>The secret identified by {@code DB_SECRET_NAME} (default:
 * {@code resortslite/db/credentials}) must be a JSON string with the shape:
 * <pre>
 * {
 *   "username": "...",
 *   "password": "...",
 *   "host":     "..."   // optional – overrides DB_HOST env-var when present
 * }
 * </pre>
 *
 * <p>Replaces the hard-coded {@code DB_USER} / {@code DB_PASS} constants that
 * previously existed in {@link BookingService} (cr-java-0069).
 */
@Component
public class DbSecretsConfig {

    private static final Logger log = LoggerFactory.getLogger(DbSecretsConfig.class);

    /** AWS Secrets Manager secret name/ARN. Override via DB_SECRET_NAME env-var. */
    @Value("${DB_SECRET_NAME:resortslite/db/credentials}")
    private String secretName;

    /** AWS region used to build the Secrets Manager client. Override via AWS_REGION env-var. */
    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    private String dbUsername;
    private String dbPassword;
    private String dbHost;

    /**
     * Fetches the secret from AWS Secrets Manager once the bean is initialised.
     * Falls back to empty strings if the secret cannot be retrieved so that the
     * application context still starts (useful for local / test profiles where
     * Secrets Manager is unavailable).
     */
    @PostConstruct
    public void loadSecrets() {
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(secretJson);

            this.dbUsername = node.path("username").asText("");
            this.dbPassword = node.path("password").asText("");
            // host is optional – only override when explicitly present in the secret
            if (node.hasNonNull("host")) {
                this.dbHost = node.path("host").asText();
            }

            log.info("Database credentials loaded from AWS Secrets Manager (secret: {})", secretName);
            client.close();

        } catch (Exception ex) {
            log.warn("Could not load DB credentials from AWS Secrets Manager (secret: {}): {}. "
                    + "Falling back to empty credentials – ensure the secret exists in region {}.",
                    secretName, ex.getMessage(), awsRegion);
            this.dbUsername = "";
            this.dbPassword = "";
        }
    }

    /** @return database username retrieved from AWS Secrets Manager. */
    public String getDbUsername() {
        return dbUsername;
    }

    /** @return database password retrieved from AWS Secrets Manager. */
    public String getDbPassword() {
        return dbPassword;
    }

    /**
     * @return database host retrieved from the secret, or {@code null} when the
     *         secret does not contain a {@code host} field (in which case the
     *         caller should fall back to the {@code DB_HOST} environment variable).
     */
    public String getDbHost() {
        return dbHost;
    }
}
