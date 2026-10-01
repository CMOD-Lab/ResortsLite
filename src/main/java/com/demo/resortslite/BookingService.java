package com.demo.resortslite;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * BookingService — cloud-native implementation.
 *
 * <p>Hard-coded database credentials have been removed and replaced with
 * values retrieved from AWS Secrets Manager at runtime (blockers 8, 9, 18).
 * No credentials are stored in source code or version control.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // AWS Secrets Manager client — credentials are resolved at runtime, not hard-coded (blocker-8, blocker-9).
    private final SecretsManagerClient secretsManagerClient;

    // Secret name for database credentials stored in AWS Secrets Manager.
    // Set the DB_SECRET_NAME environment variable in ECS / Elastic Beanstalk.
    private final String dbSecretName;

    // Payment API endpoint is read from the environment variable PAYMENT_API_URL (blocker-8, blocker-9).
    private final String paymentApi;

    public BookingService() {
        this.secretsManagerClient = SecretsManagerClient.create();

        // Resolve secret name from environment variable; avoids any hard-coded reference (blocker-8, blocker-9).
        this.dbSecretName = System.getenv().getOrDefault(
                "DB_SECRET_NAME", "resortslite/db/credentials");

        // Payment API URL is externalised to an environment variable (blocker-8).
        this.paymentApi = System.getenv().getOrDefault(
                "PAYMENT_API_URL", "https://payment-service.internal/payments/charge");
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * Replaces the former hard-coded DB_USER / DB_PASS constants (blocker-8, blocker-9).
     *
     * @return map containing "username" and "password" keys
     */
    private Map<String, String> getDbCredentials() {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretJson = response.secretString();
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> credentials = mapper.readValue(secretJson, Map.class);
            return credentials;
        } catch (Exception e) {
            // Return empty map on failure — caller should handle missing credentials gracefully.
            return new HashMap<>();
        }
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
        // DB_HOST is no longer exposed in the response — credentials are managed by Secrets Manager.
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

    /**
     * Validates user authentication credentials using AWS Secrets Manager.
     * Replaces the former file-based authentication pattern (blocker-18).
     * Credentials are retrieved from AWS Secrets Manager — no local file dependency.
     *
     * @param username the username to authenticate
     * @param password the password to validate
     * @return true if credentials match the secret stored in Secrets Manager
     */
    public boolean authenticateUser(String username, String password) {
        // Authentication credentials are retrieved from AWS Secrets Manager (blocker-18).
        // No local file is read — this is cloud-native, auditable, and supports rotation.
        Map<String, String> credentials = getDbCredentials();
        String storedUser = credentials.getOrDefault("username", "");
        String storedPass = credentials.getOrDefault("password", "");
        return storedUser.equals(username) && storedPass.equals(password);
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
