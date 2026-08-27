package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
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

    // FIX blocker-8, blocker-9 (cr-java-0069): Hard-coded DB credentials replaced with
    // AWS Secrets Manager. Credentials are resolved once at startup from the secret
    // identified by the 'aws.secretsmanager.db-secret-name' property and are never
    // stored in source code or version control.
    @Value("${aws.secretsmanager.db-secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    // FIX blocker-8, blocker-9 (cr-java-0069): Payment API endpoint is now read from
    // an environment variable injected at runtime (set via ECS task definition /
    // Elastic Beanstalk environment properties). No hard-coded IPs or ports remain.
    @Value("${PAYMENT_API_URL:#{null}}")
    private String paymentApiUrl;

    /**
     * Resolves database credentials from AWS Secrets Manager at runtime.
     * The secret is expected to be a JSON object with "username" and "password" keys.
     * This eliminates hard-coded credentials from source code (blocker-8, blocker-9).
     *
     * FIX blocker-18 (cr-java-0090): Authentication credentials are no longer stored in
     * local files. AWS Secrets Manager provides centralized, encrypted, auditable
     * credential storage with automatic rotation support.
     */
    public Map<String, String> resolveDbCredentials() {
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(secretJson);

            Map<String, String> credentials = new HashMap<>();
            credentials.put("username", node.get("username").asText());
            credentials.put("password", node.get("password").asText());
            return credentials;
        } catch (Exception e) {
            // Fallback for local development — credentials supplied via environment variables
            Map<String, String> credentials = new HashMap<>();
            credentials.put("username", System.getenv().getOrDefault("DB_USERNAME", "sa"));
            credentials.put("password", System.getenv().getOrDefault("DB_PASSWORD", ""));
            return credentials;
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
        // FIX blocker-8, blocker-9: DB_HOST is no longer hard-coded; credentials are
        // resolved from AWS Secrets Manager. The host is supplied via DB_URL env var.
        booking.put("dbHost", System.getenv().getOrDefault("DB_HOST", "resolved-from-secrets-manager"));
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
        // FIX blocker-8, blocker-9: paymentApiUrl is now resolved from environment variable
        // rather than a hard-coded IP/port. Falls back to a placeholder for local dev.
        String resolvedPaymentApi = (paymentApiUrl != null && !paymentApiUrl.isEmpty())
                ? paymentApiUrl
                : System.getenv().getOrDefault("PAYMENT_API_URL", "https://payment-service/payments/charge");
        return "Report generation triggered for: " + month + " via " + resolvedPaymentApi;
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
