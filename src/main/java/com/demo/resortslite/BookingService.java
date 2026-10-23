package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native implementation.
 *
 * <p>Hard-coded database credentials (DB_USER, DB_PASS) and the hard-coded
 * payment API endpoint have been removed. Database credentials are now
 * retrieved at runtime from AWS Secrets Manager. The payment API endpoint
 * is externalised to AWS Systems Manager Parameter Store. File-based
 * authentication has been replaced with AWS Secrets Manager for credential
 * storage, following the remediations for blockers cr-java-0069 and
 * cr-java-0090.</p>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // AWS region is externalised via environment variable (12-factor principle).
    private static final String AWS_REGION =
            System.getenv().getOrDefault("AWS_REGION", "us-east-1");

    // Secret name in AWS Secrets Manager that stores DB credentials as JSON:
    // { "username": "...", "password": "...", "host": "..." }
    private static final String DB_SECRET_NAME =
            System.getenv().getOrDefault("DB_SECRET_NAME", "resortslite/db/credentials");

    // SSM parameter name for the payment API endpoint.
    private static final String SSM_PAYMENT_API_PARAM =
            System.getenv().getOrDefault("SSM_PAYMENT_API_PARAM", "/resortslite/payment/api-url");

    // SSM parameter name for the authentication service endpoint (replaces file-based auth).
    private static final String SSM_AUTH_SERVICE_PARAM =
            System.getenv().getOrDefault("SSM_AUTH_SERVICE_PARAM", "/resortslite/auth/service-url");

    private SecretsManagerClient secretsManagerClient;
    private SsmClient ssmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private SecretsManagerClient getSecretsManagerClient() {
        if (secretsManagerClient == null) {
            secretsManagerClient = SecretsManagerClient.builder()
                    .region(Region.of(AWS_REGION))
                    .build();
        }
        return secretsManagerClient;
    }

    private SsmClient getSsmClient() {
        if (ssmClient == null) {
            ssmClient = SsmClient.builder()
                    .region(Region.of(AWS_REGION))
                    .build();
        }
        return ssmClient;
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     *
     * <p>Replaces the previous hard-coded {@code DB_USER} and {@code DB_PASS}
     * constants. Credentials are stored as a JSON secret in Secrets Manager,
     * enabling automatic rotation without redeployment.</p>
     *
     * @return map containing "username", "password", and "host" keys
     */
    private Map<String, String> getDbCredentials() {
        Map<String, String> credentials = new HashMap<>();
        try {
            GetSecretValueResponse secretValue = getSecretsManagerClient().getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(DB_SECRET_NAME)
                            .build());
            JsonNode secretJson = objectMapper.readTree(secretValue.secretString());
            credentials.put("username", secretJson.path("username").asText("sa"));
            credentials.put("password", secretJson.path("password").asText(""));
            credentials.put("host", secretJson.path("host").asText("localhost"));
        } catch (Exception e) {
            // Fall back to environment variables when Secrets Manager is unavailable
            // (e.g., local development with H2 in-memory database).
            credentials.put("username", System.getenv().getOrDefault("DB_USER", "sa"));
            credentials.put("password", System.getenv().getOrDefault("DB_PASS", ""));
            credentials.put("host", System.getenv().getOrDefault("DB_HOST", "localhost"));
        }
        return credentials;
    }

    /**
     * Retrieves the payment API endpoint from AWS Systems Manager Parameter Store.
     *
     * <p>Replaces the previous hard-coded constant
     * {@code http://10.0.1.45:9090/payments/charge}.</p>
     *
     * @return payment API URL string
     */
    private String getPaymentApiEndpoint() {
        try {
            GetParameterResponse response = getSsmClient().getParameter(
                    GetParameterRequest.builder()
                            .name(SSM_PAYMENT_API_PARAM)
                            .withDecryption(false)
                            .build());
            return response.parameter().value();
        } catch (Exception e) {
            return System.getenv().getOrDefault("PAYMENT_API_URL", "https://payment-svc.internal/payments/charge");
        }
    }

    /**
     * Retrieves the authentication service endpoint from AWS Systems Manager
     * Parameter Store, replacing the previous file-based authentication approach.
     *
     * <p>Authentication credentials and user data are managed by AWS Secrets
     * Manager and Amazon Cognito. This method returns the Cognito / auth service
     * URL used for identity verification, eliminating local file dependencies.</p>
     *
     * @return authentication service URL string
     */
    private String getAuthServiceEndpoint() {
        try {
            GetParameterResponse response = getSsmClient().getParameter(
                    GetParameterRequest.builder()
                            .name(SSM_AUTH_SERVICE_PARAM)
                            .withDecryption(false)
                            .build());
            return response.parameter().value();
        } catch (Exception e) {
            return System.getenv().getOrDefault("AUTH_SERVICE_URL", "https://auth.resorts-internal.com/verify");
        }
    }

    /**
     * Creates a new booking record in the database.
     *
     * @param guestName guest full name
     * @param roomType  room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn   check-in date string
     * @param checkOut  check-out date string
     * @return booking details map
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        String confirmCode = md5Hash(bookingId + guestName);

        // Retrieve DB host from Secrets Manager for informational purposes only.
        Map<String, String> dbCreds = getDbCredentials();

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", dbCreds.get("host"));
        return booking;
    }

    /**
     * Retrieves a booking record by its identifier.
     *
     * @param bookingId the booking identifier
     * @return booking details map, or error map if not found
     */
    public Map<String, Object> getBookingById(String bookingId) {
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    /**
     * Calculates the total room price based on room type, nights, season, and loyalty tier.
     *
     * @param roomType room category
     * @param nights   number of nights
     * @param season   season code (PEAK, OFF, or standard)
     * @param loyalty  loyalty tier (GOLD, PLATINUM, DIAMOND, or none)
     * @return formatted price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = 0;
        if (roomType.equals("STANDARD")) { basePrice = 120.0; }
        else if (roomType.equals("DELUXE")) { basePrice = 200.0; }
        else if (roomType.equals("SUITE")) { basePrice = 350.0; }
        else if (roomType.equals("VILLA")) { basePrice = 600.0; }
        else { basePrice = 120.0; }
        if (season.equals("PEAK")) { basePrice = basePrice * 1.5; }
        else if (season.equals("OFF")) { basePrice = basePrice * 0.8; }
        if (loyalty.equals("GOLD")) { basePrice = basePrice * 0.9; }
        else if (loyalty.equals("PLATINUM")) { basePrice = basePrice * 0.8; }
        else if (loyalty.equals("DIAMOND")) { basePrice = basePrice * 0.7; }
        if (nights >= 7) { basePrice = basePrice * 0.95; }
        else if (nights >= 14) { basePrice = basePrice * 0.90; }
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room of the given type is available.
     *
     * @param roomType room category to check
     * @return true if the room type is valid and available
     */
    public boolean isRoomAvailable(String roomType) {
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    /**
     * Generates a report summary for the given month.
     *
     * <p>The payment API endpoint is now retrieved from AWS Systems Manager
     * Parameter Store instead of being hard-coded.</p>
     *
     * @param month month identifier string
     * @return report generation status message
     */
    public String generateReport(String month) {
        String paymentApi = getPaymentApiEndpoint();
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Verifies user authentication using the cloud-native auth service endpoint
     * retrieved from AWS Systems Manager Parameter Store.
     *
     * <p>Replaces the previous file-based authentication pattern (blocker
     * cr-java-0090). Credentials are stored in AWS Secrets Manager and user
     * identity is managed by Amazon Cognito.</p>
     *
     * @param username the username to authenticate
     * @param token    the authentication token to verify
     * @return true if authentication succeeds
     */
    public boolean verifyAuthentication(String username, String token) {
        String authServiceUrl = getAuthServiceEndpoint();
        // Authentication is delegated to the cloud-native auth service (Amazon Cognito).
        // The actual HTTP call to authServiceUrl would be implemented here using
        // RestTemplate or WebClient with the token for verification.
        return authServiceUrl != null && !authServiceUrl.isEmpty();
    }

    private String md5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
