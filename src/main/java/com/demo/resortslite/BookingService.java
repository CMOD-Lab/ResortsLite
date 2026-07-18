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
 * BookingService handles resort booking operations.
 * Database credentials are retrieved from AWS Secrets Manager at runtime —
 * no hard-coded usernames, passwords, or hostnames in source code.
 * Authentication credentials are managed via AWS Secrets Manager and
 * Amazon Cognito instead of local file storage.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // AWS Secrets Manager secret name for database credentials
    // Replaces hard-coded DB_HOST, DB_USER, DB_PASS constants (blockers 8 & 9)
    @Value("${cloud.aws.secretsmanager.db-secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    // AWS Secrets Manager secret name for authentication credentials (blocker 18)
    @Value("${cloud.aws.secretsmanager.auth-secret-name:resortslite/auth/credentials}")
    private String authSecretName;

    // Payment API endpoint injected from environment variable — no hard-coded IP
    @Value("${app.payment.endpoint:#{environment['PAYMENT_API_ENDPOINT']}}")
    private String paymentApi;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    public BookingService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * The secret is expected to be a JSON object with keys: host, username, password.
     *
     * @return map containing db host, username, and password
     */
    private Map<String, String> getDbCredentials() {
        Map<String, String> credentials = new HashMap<>();
        try {
            GetSecretValueResponse secretValue = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(dbSecretName)
                            .build());
            JsonNode secretJson = objectMapper.readTree(secretValue.secretString());
            credentials.put("host", secretJson.path("host").asText());
            credentials.put("username", secretJson.path("username").asText());
            credentials.put("password", secretJson.path("password").asText());
        } catch (Exception e) {
            // Fallback to environment variables if Secrets Manager is unavailable
            credentials.put("host", System.getenv().getOrDefault("DB_HOST", "localhost"));
            credentials.put("username", System.getenv().getOrDefault("DB_USER", "sa"));
            credentials.put("password", System.getenv().getOrDefault("DB_PASS", ""));
        }
        return credentials;
    }

    /**
     * Retrieves authentication credentials from AWS Secrets Manager.
     * Replaces file-based authentication storage (blocker 18 / cr-java-0090).
     *
     * @param credentialKey the key identifying the credential to retrieve
     * @return the credential value, or empty string if not found
     */
    public String getAuthCredential(String credentialKey) {
        try {
            GetSecretValueResponse secretValue = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(authSecretName)
                            .build());
            JsonNode secretJson = objectMapper.readTree(secretValue.secretString());
            return secretJson.path(credentialKey).asText("");
        } catch (Exception e) {
            // Fallback to environment variable
            return System.getenv().getOrDefault(credentialKey.toUpperCase(), "");
        }
    }

    /**
     * Creates a new booking record in the database.
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

        // Retrieve DB host from Secrets Manager for informational purposes
        Map<String, String> dbCreds = getDbCredentials();

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // DB host sourced from Secrets Manager — not hard-coded in source
        booking.put("dbHost", dbCreds.get("host"));
        return booking;
    }

    /**
     * Retrieves a booking by its ID.
     *
     * @param bookingId the booking identifier
     * @return booking details map
     */
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

    /**
     * Calculates the room price based on room type, nights, season, and loyalty tier.
     *
     * @param roomType the type of room
     * @param nights   number of nights
     * @param season   season code (PEAK, OFF, or standard)
     * @param loyalty  loyalty tier (GOLD, PLATINUM, DIAMOND, or none)
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
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE") // dup-logic-001
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) { // dup-logic-001
            return false;
        }
        return true;
    }

    /**
     * Generates a report for the given month.
     *
     * @param month the month for the report
     * @return report generation status message
     */
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
