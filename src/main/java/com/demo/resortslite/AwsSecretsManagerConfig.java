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
 * AWS Secrets Manager integration for database credentials (cr-java-0069).
 *
 * <p>Retrieves the secret identified by {@code aws.secretsmanager.secret-name} from
 * AWS Secrets Manager and exposes the individual credential fields so that other
 * Spring beans can inject them via {@code @Value} or direct method calls.
 *
 * <p>The secret is expected to be stored as a JSON string with at least the
 * following keys:
 * <pre>
 * {
 *   "username": "...",
 *   "password": "..."
 * }
 * </pre>
 *
 * <p>Configure the following properties (or their environment-variable equivalents)
 * before deploying to AWS:
 * <ul>
 *   <li>{@code AWS_SECRETS_NAME}  – name / ARN of the secret in Secrets Manager</li>
 *   <li>{@code AWS_REGION}        – AWS region where the secret lives</li>
 * </ul>
 */
@Component
public class AwsSecretsManagerConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsSecretsManagerConfig.class);

    /** Name or ARN of the Secrets Manager secret that holds DB credentials. */
    @Value("${aws.secretsmanager.secret-name:${AWS_SECRETS_NAME:resortslite/db/credentials}}")
    private String secretName;

    /** AWS region where the secret is stored. */
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    private String dbUsername;
    private String dbPassword;

    /**
     * Fetches the secret from AWS Secrets Manager on application startup.
     * If the secret cannot be retrieved (e.g. running locally without AWS credentials),
     * the method logs a warning and leaves the credential fields {@code null} so that
     * the application can still start with Spring-managed datasource properties.
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
            String secretString = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretJson = mapper.readTree(secretString);

            this.dbUsername = secretJson.path("username").asText(null);
            this.dbPassword = secretJson.path("password").asText(null);

            log.info("Database credentials successfully loaded from AWS Secrets Manager "
                    + "(secret: {})", secretName);

            client.close();
        } catch (Exception e) {
            log.warn("Could not load database credentials from AWS Secrets Manager "
                    + "(secret: {}). Falling back to Spring datasource configuration. "
                    + "Reason: {}", secretName, e.getMessage());
        }
    }

    /**
     * Returns the database username retrieved from AWS Secrets Manager.
     *
     * @return username, or {@code null} if the secret could not be loaded
     */
    public String getDbUsername() {
        return dbUsername;
    }

    /**
     * Returns the database password retrieved from AWS Secrets Manager.
     *
     * @return password, or {@code null} if the secret could not be loaded
     */
    public String getDbPassword() {
        return dbPassword;
    }
}
