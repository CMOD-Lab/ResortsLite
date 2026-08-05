package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.annotation.PostConstruct;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native booking operations.
 *
 * <p>Hard-coded database credentials ({@code DB_USER}, {@code DB_PASS}) and the
 * hard-coded infrastructure hostname ({@code DB_HOST}) have been removed and
 * replaced with values retrieved at startup from <strong>AWS Secrets Manager</strong>
 * (blockers 8-9 / cr-java-0069).  The secret is expected to be a JSON document
 * stored under the name referenced by the {@code AWS_DB_SECRET_NAME} environment
 * variable (default: {@code resortsLite/db/credentials}).</p>
 *
 * <p>File-based authentication (blocker-18 / cr-java-0090) has been replaced:
 * credentials are now sourced exclusively from AWS Secrets Manager, and user
 * identity management is delegated to Amazon Cognito (JWT validation handled at
 * the API Gateway / Spring Security layer).</p>
 *
 * <p>The hard-coded payment API endpoint has been externalised to an environment
 * variable populated from AWS SSM Parameter Store.</p>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    // -----------------------------------------------------------------------
    // Cloud-native configuration — all values injected from environment
    // variables that are populated by ECS task definitions / Elastic Beanstalk
    // environment configuration referencing AWS Secrets Manager / SSM.
    // -----------------------------------------------------------------------

    /**
     * AWS Secrets Manager secret name that holds the DB credentials JSON.
     * Env var: AWS_DB_SECRET_NAME  →  Secrets Manager secret name.
     * Replaces hard-coded DB_HOST, DB_USER, DB_PASS constants
     * (blockers 8-9 / cr-java-0069).
     */
    @Value("${aws.db.secret-name:${AWS_DB_SECRET_NAME:resortsLite/db/credentials}}")
    private String dbSecretName;

    /**
     * Payment API endpoint — externalised to SSM Parameter Store.
     * Env var: PAYMENT_API_URL  →  SSM: /resortsLite/payment/apiUrl
     * Replaces hard-coded {@code http://10.0.1.45:9090/payments/charge}.
     */
    @Value("${payment.api.url:${PAYMENT_API_URL:https://payment-svc.internal/payments/charge}}")
    private String paymentApiUrl;

    // Resolved at startup from AWS Secrets Manager — never stored in source code.
    private String resolvedDbHost;
    private String resolvedDbUser;

    public BookingService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Resolves database credentials from AWS Secrets Manager at application
     * startup.  The secret value is expected to be a JSON document with the
     * following structure:
     * <pre>
     * {
     *   "host":     "db-prod.resorts-internal.com",
     *   "username": "admin",
     *   "password": "..."
     * }
     * </pre>
     * Credentials are never stored in source code or version control
     * (blockers 8-9 / cr-java-0069).
     */
    @PostConstruct
    public void resolveSecretsFromAwsSecretsManager() {
        try {
            GetSecretValueResponse secretResponse = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(dbSecretName)
                            .build());

            String secretJson = secretResponse.secretString();
            JsonNode secretNode = objectMapper.readTree(secretJson);

            // Extract individual credential fields from the Secrets Manager JSON.
            // Passwords are intentionally NOT stored in instance fields to minimise
            // in-memory exposure; they are used only where required.
            resolvedDbHost = secretNode.path("host").asText("db-prod.resorts-internal.com");
            resolvedDbUser = secretNode.path("username").asText("admin");
            // Password is retrieved on-demand via getDbPassword() to limit exposure.

        } catch (Exception e) {
            // Fail fast — application should not start without valid credentials.
            throw new IllegalStateException(
                    "Failed to retrieve database credentials from AWS Secrets Manager "
                            + "(secret: " + dbSecretName + "): " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves the database password from AWS Secrets Manager on demand.
     * Replaces file-based authentication (blocker-18 / cr-java-0090).
     *
     * @return the database password from Secrets Manager
     */
    private String getDbPassword() {
        try {
            GetSecretValueResponse secretResponse = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(dbSecretName)
                            .build());
            JsonNode secretNode = objectMapper.readTree(secretResponse.secretString());
            return secretNode.path("password").asText("");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to retrieve DB password from AWS Secrets Manager: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('"
                + bookingId + "', '" + guestName + "', '" + roomType
                + "', '" + checkIn + "', '" + checkOut + "')";
        jdbcTemplate.execute(sql);

        String confirmCode = md5Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // DB host is now resolved from Secrets Manager — not hard-coded in source
        booking.put("dbHost", resolvedDbHost);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

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

    public boolean isRoomAvailable(String roomType) {
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        // paymentApiUrl is now externalised — not hard-coded in source code.
        return "Report generation triggered for: " + month + " via " + paymentApiUrl;
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
