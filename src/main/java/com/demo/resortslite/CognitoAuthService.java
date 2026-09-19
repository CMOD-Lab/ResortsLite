package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * cr-java-0090 FIX: Amazon Cognito integration for user identity management.
 *
 * <p>Replaces any file-based or locally stored authentication credentials/user data
 * with Amazon Cognito as the authoritative identity provider. This service provides:
 * <ul>
 *   <li>Centralized, encrypted user identity storage in Amazon Cognito User Pools</li>
 *   <li>Auditable authentication events via AWS CloudTrail</li>
 *   <li>Built-in user lifecycle management (create, update, disable, delete)</li>
 *   <li>Horizontal scalability — no local state or file system dependency</li>
 * </ul>
 *
 * <p>Configuration (via environment variables or application.properties):
 * <pre>
 *   AWS_COGNITO_USER_POOL_ID  – Cognito User Pool ID (e.g., us-east-1_AbCdEfGhI)
 *   AWS_COGNITO_CLIENT_ID     – Cognito App Client ID
 *   AWS_REGION                – AWS region where the User Pool is hosted
 * </pre>
 *
 * <p>Required IAM permissions for the application's execution role:
 * <ul>
 *   <li>{@code cognito-idp:AdminGetUser}</li>
 *   <li>{@code cognito-idp:GetUser}</li>
 * </ul>
 */
@Service
public class CognitoAuthService {

    private static final Logger logger = Logger.getLogger(CognitoAuthService.class.getName());

    /** Cognito User Pool ID — injected from environment variable AWS_COGNITO_USER_POOL_ID. */
    @Value("${aws.cognito.user-pool-id:${AWS_COGNITO_USER_POOL_ID:}}")
    private String userPoolId;

    /** Cognito App Client ID — injected from environment variable AWS_COGNITO_CLIENT_ID. */
    @Value("${aws.cognito.client-id:${AWS_COGNITO_CLIENT_ID:}}")
    private String clientId;

    /** AWS region — shared with the rest of the application. */
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Validates that a guest identity exists in the Amazon Cognito User Pool.
     *
     * <p>This replaces any file-based user lookup. The Cognito User Pool is the
     * single source of truth for user identity — no local files or in-memory maps
     * are consulted.
     *
     * @param username the Cognito username (typically the guest's email address)
     * @return {@code true} if the user exists and is enabled in the User Pool;
     *         {@code false} otherwise
     */
    public boolean validateGuestIdentity(String username) {
        if (userPoolId == null || userPoolId.isEmpty()) {
            // Graceful degradation for local development when Cognito is not configured.
            logger.warning("[CognitoAuthService] AWS_COGNITO_USER_POOL_ID is not set. "
                    + "Skipping Cognito identity validation (local-dev mode).");
            return true;
        }

        try {
            CognitoIdentityProviderClient cognitoClient = buildCognitoClient();

            AdminGetUserRequest request = AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse response = cognitoClient.adminGetUser(request);

            // User must be in CONFIRMED status and not disabled.
            boolean isEnabled = response.enabled();
            String userStatus = response.userStatusAsString();
            boolean isConfirmed = "CONFIRMED".equalsIgnoreCase(userStatus);

            logger.info("[CognitoAuthService] Identity validation for user '" + username
                    + "': enabled=" + isEnabled + ", status=" + userStatus);

            return isEnabled && isConfirmed;

        } catch (UserNotFoundException e) {
            logger.warning("[CognitoAuthService] User '" + username + "' not found in Cognito User Pool.");
            return false;
        } catch (Exception e) {
            logger.severe("[CognitoAuthService] Error validating identity for user '" + username
                    + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * Retrieves user profile attributes from Amazon Cognito for the authenticated guest.
     *
     * <p>Replaces any file-based user data storage. All user attributes (email, name,
     * phone, loyalty tier, etc.) are stored in and retrieved from the Cognito User Pool.
     *
     * @param username the Cognito username
     * @return a map of Cognito user attributes, or an empty map if the user is not found
     */
    public Map<String, String> getGuestAttributes(String username) {
        Map<String, String> attributes = new HashMap<>();

        if (userPoolId == null || userPoolId.isEmpty()) {
            logger.warning("[CognitoAuthService] AWS_COGNITO_USER_POOL_ID is not set. "
                    + "Returning empty attributes (local-dev mode).");
            return attributes;
        }

        try {
            CognitoIdentityProviderClient cognitoClient = buildCognitoClient();

            AdminGetUserRequest request = AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse response = cognitoClient.adminGetUser(request);

            for (AttributeType attr : response.userAttributes()) {
                attributes.put(attr.name(), attr.value());
            }

            logger.info("[CognitoAuthService] Retrieved " + attributes.size()
                    + " attributes for user '" + username + "' from Cognito.");

        } catch (UserNotFoundException e) {
            logger.warning("[CognitoAuthService] User '" + username + "' not found in Cognito User Pool.");
        } catch (Exception e) {
            logger.severe("[CognitoAuthService] Error retrieving attributes for user '" + username
                    + "': " + e.getMessage());
        }

        return attributes;
    }

    /**
     * Validates a Cognito access token and retrieves the associated user identity.
     *
     * <p>Used to authenticate API requests that carry a Cognito JWT access token
     * (e.g., issued by the Cognito Hosted UI or a mobile SDK). The token is verified
     * against the Cognito User Pool — no local token store or file is consulted.
     *
     * @param accessToken the Cognito JWT access token from the Authorization header
     * @return a map containing the authenticated user's identity attributes,
     *         or an empty map if the token is invalid or expired
     */
    public Map<String, String> validateAccessToken(String accessToken) {
        Map<String, String> userInfo = new HashMap<>();

        if (accessToken == null || accessToken.isEmpty()) {
            logger.warning("[CognitoAuthService] Access token is null or empty.");
            return userInfo;
        }

        try {
            CognitoIdentityProviderClient cognitoClient = buildCognitoClient();

            GetUserRequest request = GetUserRequest.builder()
                    .accessToken(accessToken)
                    .build();

            GetUserResponse response = cognitoClient.getUser(request);

            userInfo.put("username", response.username());
            for (AttributeType attr : response.userAttributes()) {
                userInfo.put(attr.name(), attr.value());
            }

            logger.info("[CognitoAuthService] Access token validated for user: " + response.username());

        } catch (Exception e) {
            logger.warning("[CognitoAuthService] Access token validation failed: " + e.getMessage());
        }

        return userInfo;
    }

    /**
     * Builds a {@link CognitoIdentityProviderClient} for the configured AWS region.
     *
     * <p>The client uses the default AWS credential chain (IAM role, environment
     * variables, ~/.aws/credentials) — no credentials are stored in files or code.
     */
    private CognitoIdentityProviderClient buildCognitoClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
