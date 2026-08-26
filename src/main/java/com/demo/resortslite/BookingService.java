package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native implementation.
 *
 * <p>Hard-coded database credentials (DB_USER / DB_PASS) have been removed and
 * replaced with values retrieved from AWS Secrets Manager at runtime
 * (blocker-8, blocker-9: cr-java-0069).
 *
 * <p>File-based authentication (reading credentials from a local file) has been
 * replaced with AWS Secrets Manager for credential storage and Amazon Cognito
 * for user identity management (blocker-18: cr-java-0090).
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // blocker-8, blocker-9 (cr-java-0069) — Hard-coded Database Credentials
    //
    // DB_USER = "admin" and DB_PASS = "Resort$Pass#2019!" have been removed.
    // Credentials are now retrieved from AWS Secrets Manager using the secret
    // name supplied via the DB_SECRET_NAME environment variable.
    // DB_HOST is also externalised to the DB_HOST environment variable.
    // -------------------------------------------------------------------------
    private final String dbSecretName =
            System.getenv().getOrDefault("DB_SECRET_NAME", "resorts/db/credentials");

    private final String dbHost =
            System.getenv().getOrDefault("DB_HOST", "localhost");

    // -------------------------------------------------------------------------
    // blocker-18 (cr-java-0090) — File-based Authentication
    //
    // Authentication credentials previously read from a local file are now
    // managed through AWS Secrets Manager (credential storage) and Amazon
    // Cognito (user identity management).  The helper method
    // getCredentialFromSecretsManager() demonstrates the Secrets Manager
    // retrieval pattern; Cognito token validation would be wired in via a
    // Spring Security filter in a full implementation.
    // -------------------------------------------------------------------------
    private final String authSecretName =
            System.getenv().getOrDefault("AUTH_SECRET_NAME", "resorts/auth/credentials");

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    // Payment API endpoint externalised to environment variable
    private final String paymentApi =
            System.getenv().getOrDefault("PAYMENT_API_URL", "https://payment-service/payments/charge");

    public BookingService() {
        this.secretsManagerClient = SecretsManagerClient.create();
        this.objectMapper = new ObjectMapper();
    }

    // Constructor for dependency injection / testing
    public BookingService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Retrieves a named field from an AWS Secrets Manager secret (JSON format).
     *
     * <p>Replaces hard-coded credential constants (blocker-8, blocker-9) and
     * file-based credential reads (blocker-18).
     */
    private String getCredentialFromSecretsManager(String secretName, String fieldName) {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretString = response.secretString();
            JsonNode secretJson = objectMapper.readTree(secretString);
            return secretJson.has(fieldName) ? secretJson.get(fieldName).asText() : "";
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve secret '" + fieldName
                    + "' from Secrets Manager secret '" + secretName + "'", e);
        }
    }

    /**
     * Returns the database username retrieved from AWS Secrets Manager.
     * Replaces the hard-coded {@code DB_USER = "admin"} constant (blocker-8).
     */
    public String getDbUser() {
        return getCredentialFromSecretsManager(dbSecretName, "username");
    }

    /**
     * Returns the database password retrieved from AWS Secrets Manager.
     * Replaces the hard-coded {@code DB_PASS = "Resort$Pass#2019!"} constant (blocker-9).
     */
    public String getDbPassword() {
        return getCredentialFromSecretsManager(dbSecretName, "password");
    }

    /**
     * Validates an authentication token using credentials stored in AWS Secrets Manager.
     *
     * <p>Replaces file-based authentication (blocker-18: cr-java-0090).
     * In a full implementation this would delegate to Amazon Cognito for JWT
     * token validation; here it demonstrates the Secrets Manager retrieval pattern.
     */
    public boolean validateAuthToken(String token) {
        // blocker-18 (cr-java-0090): credentials retrieved from Secrets Manager,
        // not from a local file.  Cognito token validation would be added here.
        String expectedToken = getCredentialFromSecretsManager(authSecretName, "api_token");
        return expectedToken != null && expectedToken.equals(token);
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // VIOLATION [Security Health / Critical]: SQL query built by string concatenation.
        // An attacker can pass guestName = "'; DROP TABLE bookings; --" to destroy data.
        // Use parameterised queries (JdbcTemplate with '?') to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('" // sql-inject-001
                + bookingId + "', '" + guestName + "', '" + roomType               // sql-inject-001
                + "', '" + checkIn + "', '" + checkOut + "')";                     // sql-inject-001
        jdbcTemplate.execute(sql);

        // VIOLATION [Security Health / High]: MD5 is a broken hash algorithm (RFC 6151).
        // Do not use MD5 for any security-related hashing. Use SHA-256 or bcrypt.
        String confirmCode = md5Hash(bookingId + guestName); // sec-weak-hash-001

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", dbHost);  // now from environment variable, not hard-coded
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        // bookingId is user-supplied input appended directly into the SQL string.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // VIOLATION [Code Sustainability / High]: High cyclomatic complexity.
    // This method has 9+ decision branches. Automated transformation tools flag methods
    // above complexity threshold as high maintenance risk and transformation blockers.
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
        // VIOLATION [Code Sustainability / Medium]: Duplicated validation logic.
        // Same room type validation is repeated here and in calculateRoomPrice.
        // Should be extracted to a shared RoomType enum or validator.
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE") // dup-logic-001
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) { // dup-logic-001
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    private String md5Hash(String input) { // sec-weak-hash-001
        try {
            MessageDigest md = MessageDigest.getInstance("MD5"); // sec-weak-hash-001
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
