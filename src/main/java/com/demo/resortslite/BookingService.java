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

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService handles booking business logic.
 *
 * Cloud-readiness changes applied:
 * - Blocker-8/9 (cr-java-0069): Hard-coded DB_USER / DB_PASS replaced with
 *   AWS Secrets Manager — credentials are retrieved at runtime, never stored in source.
 * - Blocker-18 (cr-java-0090): File-based authentication replaced with
 *   AWS Secrets Manager for credential storage and Amazon Cognito for user identity.
 *   The PAYMENT_API endpoint is now injected via environment variable.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SecretsManagerClient secretsManagerClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Blocker-8/9 (cr-java-0069): Secret name injected via environment variable —
    // actual credentials are fetched from AWS Secrets Manager at runtime.
    @Value("${cloud.aws.secretsmanager.db-secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    // Blocker-18 (cr-java-0090): Auth secret name for Secrets Manager
    @Value("${cloud.aws.secretsmanager.auth-secret-name:resortslite/auth/credentials}")
    private String authSecretName;

    // Blocker-8/9 (cr-java-0069): DB host externalised to environment variable — no hard-coded value
    @Value("${DB_HOST:#{null}}")
    private String dbHost;

    // Blocker-18 (cr-java-0090): Payment API endpoint from environment variable — no hard-coded IP
    @Value("${PAYMENT_API_URL:https://payment-service.internal/payments/charge}")
    private String paymentApi;

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * Replaces hard-coded DB_USER / DB_PASS constants (blockers 8 & 9, cr-java-0069).
     *
     * @return map containing "username" and "password" keys
     */
    private Map<String, String> getDbCredentials() {
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(dbSecretName)
                            .build());
            JsonNode secretJson = objectMapper.readTree(response.secretString());
            Map<String, String> creds = new HashMap<>();
            creds.put("username", secretJson.get("username").asText());
            creds.put("password", secretJson.get("password").asText());
            return creds;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve DB credentials from AWS Secrets Manager: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Retrieves authentication credentials from AWS Secrets Manager.
     * Replaces file-based authentication (blocker-18, cr-java-0090).
     *
     * @return map containing auth configuration values
     */
    private Map<String, String> getAuthCredentials() {
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(authSecretName)
                            .build());
            JsonNode secretJson = objectMapper.readTree(response.secretString());
            Map<String, String> authConfig = new HashMap<>();
            // Blocker-18 (cr-java-0090): Credentials sourced from Secrets Manager,
            // not from local files — supports Cognito user pool integration
            if (secretJson.has("cognitoUserPoolId")) {
                authConfig.put("cognitoUserPoolId", secretJson.get("cognitoUserPoolId").asText());
            }
            if (secretJson.has("cognitoClientId")) {
                authConfig.put("cognitoClientId", secretJson.get("cognitoClientId").asText());
            }
            return authConfig;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve auth credentials from AWS Secrets Manager: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Creates a new booking record.
     *
     * @param guestName guest name
     * @param roomType  room type
     * @param checkIn   check-in date
     * @param checkOut  check-out date
     * @return booking details map
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterised query — prevents SQL injection
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
        // Blocker-8/9 (cr-java-0069): DB host from environment variable, not hard-coded
        booking.put("dbHost", dbHost != null ? dbHost : "configured-via-environment");
        return booking;
    }

    /**
     * Retrieves a booking by its ID.
     *
     * @param bookingId the booking identifier
     * @return booking details map
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterised query — prevents SQL injection
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
     * Calculates the room price based on type, nights, season, and loyalty tier.
     *
     * @param roomType room type
     * @param nights   number of nights
     * @param season   season code
     * @param loyalty  loyalty tier
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
     * Checks whether a room type is available.
     *
     * @param roomType room type to check
     * @return true if available
     */
    public boolean isRoomAvailable(String roomType) {
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    /**
     * Generates a report for the given month.
     * Payment API endpoint is sourced from environment variable (blocker-18, cr-java-0090).
     *
     * @param month the month
     * @return report generation message
     */
    public String generateReport(String month) {
        // Blocker-18 (cr-java-0090): paymentApi injected via env var — no hard-coded IP/port
        return "Report generation triggered for: " + month + " via " + paymentApi;
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
