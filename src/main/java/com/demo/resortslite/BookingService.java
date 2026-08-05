package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
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

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Replaced hard-coded DB credentials (cr-java-0069) with AWS Secrets Manager.
    // The secret name is read from the environment variable DB_SECRET_NAME.
    // Credentials are fetched at runtime and never stored in source code.
    private final String dbHost;
    private final String dbUser;
    private final String dbPass;

    // Replaced hard-coded payment API URL (cr-java-0071) with an environment variable
    // so it can be set per environment without code changes.
    private final String paymentApi;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    public BookingService() {
        this.secretsManagerClient = SecretsManagerClient.create();
        this.objectMapper = new ObjectMapper();

        // Retrieve DB credentials from AWS Secrets Manager (cr-java-0069).
        // The secret is expected to be a JSON object: {"host":"...","username":"...","password":"..."}
        String secretName = System.getenv("DB_SECRET_NAME");
        if (secretName == null || secretName.isEmpty()) {
            secretName = "resorts/db/credentials";
        }

        String resolvedHost = "localhost";
        String resolvedUser = "sa";
        String resolvedPass = "";

        try {
            GetSecretValueRequest secretRequest = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();
            GetSecretValueResponse secretResponse = secretsManagerClient.getSecretValue(secretRequest);
            String secretJson = secretResponse.secretString();
            JsonNode secretNode = objectMapper.readTree(secretJson);
            if (secretNode.has("host")) {
                resolvedHost = secretNode.get("host").asText();
            }
            if (secretNode.has("username")) {
                resolvedUser = secretNode.get("username").asText();
            }
            if (secretNode.has("password")) {
                resolvedPass = secretNode.get("password").asText();
            }
        } catch (Exception e) {
            // Fall back to environment variables if Secrets Manager is unavailable.
            String envHost = System.getenv("DB_HOST");
            String envUser = System.getenv("DB_USER");
            String envPass = System.getenv("DB_PASS");
            if (envHost != null) resolvedHost = envHost;
            if (envUser != null) resolvedUser = envUser;
            if (envPass != null) resolvedPass = envPass;
        }

        this.dbHost = resolvedHost;
        this.dbUser = resolvedUser;
        this.dbPass = resolvedPass;

        // Payment API URL from environment variable — no hard-coded endpoint (cr-java-0071).
        String paymentEnv = System.getenv("PAYMENT_API_URL");
        this.paymentApi = (paymentEnv != null && !paymentEnv.isEmpty())
                ? paymentEnv
                : "https://payment-service.internal/payments/charge";
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
        // dbHost is now resolved from AWS Secrets Manager — not hard-coded (cr-java-0069).
        booking.put("dbHost", dbHost);
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
        // paymentApi is now resolved from environment variable — not hard-coded (cr-java-0071).
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
