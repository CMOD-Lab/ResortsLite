package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.annotation.PostConstruct;
import java.util.logging.Logger;

/**
 * AWS Secrets Manager integration component.
 *
 * Retrieves database credentials (username and password) from AWS Secrets Manager
 * at application startup, replacing hard-coded credential constants (cr-java-0069).
 *
 * The secret is expected to be stored in AWS Secrets Manager as a JSON string:
 *   { "username": "...", "password": "...", "host": "..." }
 *
 * Configuration via environment variables / application.properties:
 *   AWS_DB_SECRET_NAME  (or app.db.secret.name)  – name/ARN of the secret
 *   AWS_REGION          (or aws.region)           – AWS region where the secret lives
 */
@Component
public class AwsSecretsManagerConfig {

    private static final Logger logger = Logger.getLogger(AwsSecretsManagerConfig.class.getName());

    @Value("${app.db.secret.name:${AWS_DB_SECRET_NAME:resortslite/db/credentials}}")
    private String dbSecretName;

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /** Database username resolved from AWS Secrets Manager. */
    private String dbUser;

    /** Database password resolved from AWS Secrets Manager. */
    private String dbPass;

    /** Database host resolved from AWS Secrets Manager (optional field). */
    private String dbHost;

    /**
     * Fetches the database secret from AWS Secrets Manager on startup.
     * Falls back to environment variables DB_USER / DB_PASS / DB_HOST when
     * running locally without AWS credentials (e.g., unit tests, local dev).
     */
    @PostConstruct
    public void loadSecrets() {
        // Allow local-dev override via plain environment variables so the app
        // can still start without AWS credentials during development/testing.
        String envUser = System.getenv("DB_USER");
        String envPass = System.getenv("DB_PASS");
        String envHost = System.getenv("DB_HOST");

        if (envUser != null && envPass != null) {
            logger.info("[AwsSecretsManagerConfig] Using DB credentials from environment variables (local-dev mode).");
            this.dbUser = envUser;
            this.dbPass = envPass;
            this.dbHost = (envHost != null) ? envHost : "";
            return;
        }

        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretString = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretJson = mapper.readTree(secretString);

            this.dbUser = secretJson.has("username") ? secretJson.get("username").asText() : "";
            this.dbPass = secretJson.has("password") ? secretJson.get("password").asText() : "";
            this.dbHost = secretJson.has("host")     ? secretJson.get("host").asText()     : "";

            logger.info("[AwsSecretsManagerConfig] Successfully loaded DB credentials from AWS Secrets Manager secret: " + dbSecretName);

        } catch (Exception e) {
            // Log the error but do not expose credential details in the message.
            logger.severe("[AwsSecretsManagerConfig] Failed to load DB credentials from AWS Secrets Manager ("
                    + dbSecretName + "): " + e.getMessage()
                    + ". Ensure the secret exists and the IAM role has secretsmanager:GetSecretValue permission.");
            // Re-throw so the application context fails fast rather than starting with empty credentials.
            throw new IllegalStateException(
                    "Unable to retrieve database credentials from AWS Secrets Manager. "
                    + "Set DB_USER and DB_PASS environment variables for local development.", e);
        }
    }

    /** @return Database username retrieved from AWS Secrets Manager. */
    public String getDbUser() {
        return dbUser;
    }

    /** @return Database password retrieved from AWS Secrets Manager. */
    public String getDbPass() {
        return dbPass;
    }

    /** @return Database host retrieved from AWS Secrets Manager. */
    public String getDbHost() {
        return dbHost;
    }
}
