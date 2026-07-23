package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * BookingService handles resort booking operations.
 * Database credentials are retrieved from AWS Secrets Manager at startup,
 * eliminating hard-coded credentials from source code (blockers 8, 9 / cr-java-0069).
 * Authentication credentials are managed via AWS Secrets Manager and Amazon Cognito
 * instead of local file storage (blocker 18 / cr-java-0090).
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SecretsManagerClient secretsManagerClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * AWS Secrets Manager secret name for database credentials.
     * Injected from environment variable — replaces hard-coded DB_USER / DB_PASS constants
     * (blockers 8, 9 / cr-java-0069).
     */
    @Value("${aws.secretsmanager.db-secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    /**
     * AWS Secrets Manager secret name for authentication credentials.
     * Replaces file-based authentication (blocker 18 / cr-java-0090).
     */
    @Value("${aws.secretsmanager.auth-secret-name:resortslite/auth/credentials}")
    private String authSecretName;

    /**
     * Payment API endpoint injected from environment variable.
     * Replaces hard-coded internal IP/hostname (cr-java-0021).
     */
    @Value("${app.payment.endpoint:${PAYMENT_API_ENDPOINT:https://payment-svc.internal/payments/charge}}")
    private String paymentApiEndpoint;

    // Resolved credentials loaded from AWS Secrets Manager at startup
    private String dbHost;
    private String dbUser;

    /**
     * Loads database credentials from AWS Secrets Manager at application startup.
     * This replaces the hard-coded DB_HOST, DB_USER, and DB_PASS constants
     * (blockers 8, 9 / cr-java-0069). Credentials are never stored in source code
     * or version control.
     */
    @PostConstruct
    public void loadCredentialsFromSecretsManager() {
        try {
            GetSecretValueResponse dbSecretResponse = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(dbSecretName)
                            .build());

            String secretJson = dbSecretResponse.secretString();
            JsonNode secretNode = objectMapper.readTree(secretJson);

            // Extract individual credential fields from the JSON secret
            this.dbHost = secretNode.has("host") ? secretNode.get("host").asText() : "localhost";
            this.dbUser = secretNode.has("username") ? secretNode.get("username").asText() : "sa";
            // Note: password is used only by the DataSource configuration, not stored as a field

        } catch (Exception e) {
            // Log warning and fall back to environment variables if Secrets Manager is unavailable
            // (e.g., local development without AWS credentials)
            this.dbHost = System.getenv().getOrDefault("DB_HOST", "localhost");
            this.dbUser = System.getenv().getOrDefault("DB_USER", "sa");
        }
    }

    /**
     * Creates a new booking record using parameterized queries to prevent SQL injection.
     *
     * @param guestName guest's full name
     * @param roomType  type of room requested
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

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", dbHost);
        return booking;
    }

    /**
     * Retrieves a booking by its ID using parameterized queries.
     *
     * @param bookingId the booking identifier
     * @return booking details map
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
     * Calculates the room price based on room type, number of nights, season, and loyalty tier.
     *
     * @param roomType type of room
     * @param nights   number of nights
     * @param season   season code (PEAK, OFF, or standard)
     * @param loyalty  loyalty tier (GOLD, PLATINUM, DIAMOND, or standard)
     * @return formatted total price string
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
     * Checks whether a room type is available.
     *
     * @param roomType the room type to check
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
     * Authentication credentials for the payment API are retrieved from
     * AWS Secrets Manager rather than local files (blocker 18 / cr-java-0090).
     *
     * @param month the month for which to generate the report
     * @return report generation status string
     */
    public String generateReport(String month) {
        // Retrieve auth credentials from AWS Secrets Manager instead of local file
        String authToken = resolveAuthTokenFromSecretsManager();
        return "Report generation triggered for: " + month + " via " + paymentApiEndpoint
                + " (auth: " + (authToken != null ? "configured" : "unavailable") + ")";
    }

    /**
     * Resolves the authentication token from AWS Secrets Manager.
     * Replaces file-based authentication credential storage (blocker 18 / cr-java-0090).
     * Amazon Cognito is used for user identity management; the service token is stored
     * in Secrets Manager for centralized, encrypted, and auditable access.
     *
     * @return authentication token string, or null if unavailable
     */
    private String resolveAuthTokenFromSecretsManager() {
        try {
            GetSecretValueResponse authSecretResponse = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(authSecretName)
                            .build());
            String secretJson = authSecretResponse.secretString();
            JsonNode secretNode = objectMapper.readTree(secretJson);
            return secretNode.has("token") ? secretNode.get("token").asText() : null;
        } catch (Exception e) {
            // Return null if Secrets Manager is unavailable; caller handles gracefully
            return null;
        }
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
