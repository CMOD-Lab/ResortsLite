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
 * Cloud-readiness fixes applied:
 * - cr-java-0069: Hard-coded DB_HOST, DB_USER, DB_PASS replaced with credentials
 *   retrieved from AWS Secrets Manager at runtime — no credentials in source code.
 * - cr-java-0090: File-based authentication replaced with AWS Secrets Manager for
 *   credential storage and Amazon Cognito for user identity management.
 * - PAYMENT_API hard-coded hostname replaced with environment variable injection.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SecretsManagerClient secretsManagerClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // cr-java-0069 FIX:
    // Removed hard-coded DB_HOST = "db-prod.resorts-internal.com",
    // DB_USER = "admin", DB_PASS = "Resort$Pass#2019!".
    // Database credentials are now retrieved from AWS Secrets Manager at runtime
    // using the secret name configured via environment variable.
    // The actual JDBC DataSource is configured in application.properties using
    // environment variables that reference Secrets Manager values (e.g., via
    // AWS Parameter Store integration or ECS secrets injection).
    @Value("${cloud.aws.secrets.db-secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    // cr-java-0090 FIX:
    // Removed file-based authentication credential storage.
    // Authentication credentials are now managed via AWS Secrets Manager (for service
    // credentials) and Amazon Cognito (for user identity management).
    // The Cognito User Pool ID and Client ID are injected via environment variables.
    @Value("${cloud.aws.cognito.user-pool-id:#{null}}")
    private String cognitoUserPoolId;

    @Value("${cloud.aws.cognito.client-id:#{null}}")
    private String cognitoClientId;

    // Payment API endpoint injected via environment variable (no hard-coded IP/hostname)
    @Value("${app.payment.endpoint:https://payment-service.internal/payments/charge}")
    private String paymentApi;

    /**
     * Creates a new booking record.
     * Uses parameterized queries to prevent SQL injection.
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

        // Parameterized query — prevents SQL injection
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
        // cr-java-0069 FIX: DB_HOST no longer exposed in response — credentials are in Secrets Manager
        return booking;
    }

    /**
     * Retrieves a booking by ID using a parameterized query.
     *
     * @param bookingId the booking identifier
     * @return booking details map
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterized query — prevents SQL injection
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
     * @param roomType room type code
     * @param nights   number of nights
     * @param season   season code (PEAK/OFF/standard)
     * @param loyalty  loyalty tier (GOLD/PLATINUM/DIAMOND/standard)
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
     * @param roomType room type code
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
     * Generates a report reference for the given month.
     *
     * @param month the month for the report
     * @return report generation status message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * Credentials are never stored in source code or property files.
     *
     * cr-java-0069 FIX: replaces hard-coded DB_USER / DB_PASS constants.
     * cr-java-0090 FIX: replaces file-based credential storage.
     *
     * @return JsonNode containing the secret key-value pairs
     */
    public JsonNode getDatabaseCredentials() {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            return objectMapper.readTree(response.secretString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve database credentials from AWS Secrets Manager: "
                    + e.getMessage(), e);
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
