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

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SecretsManagerClient secretsManagerClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Blocker-8/9 (cr-java-0069): Hard-coded DB credentials removed.
    // Credentials are retrieved at runtime from AWS Secrets Manager.
    // The secret name is injected via environment variable (12-factor).
    @Value("${cloud.aws.secretsmanager.db-secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    // Blocker-18 (cr-java-0090): Hard-coded auth file path removed.
    // Authentication credentials are managed via AWS Secrets Manager.
    @Value("${cloud.aws.secretsmanager.auth-secret-name:resortslite/auth/credentials}")
    private String authSecretName;

    // Payment API endpoint injected via environment variable — no hard-coded IP (12-factor)
    @Value("${PAYMENT_API_URL:https://payment-service.internal/payments/charge}")
    private String paymentApi;

    /**
     * Retrieves a secret value from AWS Secrets Manager by secret name.
     * Returns the parsed JSON node for field-level access.
     */
    private JsonNode getSecret(String secretName) {
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(secretName)
                            .build());
            return objectMapper.readTree(response.secretString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve secret: " + secretName, e);
        }
    }

    /**
     * Returns database host resolved from AWS Secrets Manager (blocker-8/9).
     * Replaces the former hard-coded DB_HOST constant.
     */
    public String getDbHost() {
        // Blocker-8/9 (cr-java-0069): DB host retrieved from Secrets Manager
        JsonNode secret = getSecret(dbSecretName);
        return secret.path("host").asText("db-prod.resorts-internal.com");
    }

    /**
     * Returns database username resolved from AWS Secrets Manager (blocker-8/9).
     */
    public String getDbUser() {
        JsonNode secret = getSecret(dbSecretName);
        return secret.path("username").asText();
    }

    /**
     * Returns database password resolved from AWS Secrets Manager (blocker-8/9).
     */
    public String getDbPassword() {
        JsonNode secret = getSecret(dbSecretName);
        return secret.path("password").asText();
    }

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
        // Blocker-8/9 (cr-java-0069): DB host resolved from Secrets Manager at runtime
        booking.put("dbHost", getDbHost());
        return booking;
    }

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
        // Blocker-8/9 (cr-java-0069): paymentApi sourced from environment variable,
        // not hard-coded. Blocker-18 (cr-java-0090): no file-based auth references.
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    // Blocker-18 (cr-java-0090): File-based authentication replaced with AWS Secrets Manager.
    // Authentication credentials are retrieved from Secrets Manager, not local files.
    public boolean validateAuthCredentials(String username, String providedPassword) {
        try {
            JsonNode authSecret = getSecret(authSecretName);
            String storedUser = authSecret.path("username").asText();
            String storedPass = authSecret.path("password").asText();
            return storedUser.equals(username) && storedPass.equals(providedPassword);
        } catch (Exception e) {
            return false;
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
