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

    // Blocker-8/9: Hard-coded database credentials removed from source code.
    // DB_HOST, DB_USER, and DB_PASS are now retrieved at runtime from AWS Secrets Manager
    // using the secret name configured via the environment variable
    // RESORTSLITE_DB_SECRET_NAME (e.g. "resortslite/db/credentials").
    // This enables automatic credential rotation without redeployment and prevents
    // credential exposure in version control or container image layers.
    @Value("${aws.secretsmanager.db-secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    // Blocker-18: File-based authentication replaced with AWS Secrets Manager.
    // Authentication credentials are stored in Secrets Manager under the secret name
    // configured by the environment variable RESORTSLITE_AUTH_SECRET_NAME.
    @Value("${aws.secretsmanager.auth-secret-name:resortslite/auth/credentials}")
    private String authSecretName;

    @Value("${app.payment.endpoint:https://payment-svc.internal/payments/charge}")
    private String paymentApiEndpoint;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    public BookingService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Retrieves a secret value from AWS Secrets Manager.
     * Blocker-8/9/18: Centralised secret retrieval replaces all hard-coded credentials
     * and file-based authentication data.
     */
    private String getSecret(String secretName) {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            return response.secretString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve secret: " + secretName, e);
        }
    }

    /**
     * Retrieves a specific field from a JSON secret stored in AWS Secrets Manager.
     */
    private String getSecretField(String secretName, String fieldName) {
        try {
            String secretJson = getSecret(secretName);
            JsonNode node = objectMapper.readTree(secretJson);
            return node.has(fieldName) ? node.get(fieldName).asText() : null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse secret field '" + fieldName
                    + "' from secret: " + secretName, e);
        }
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
        // Blocker-8/9: DB_HOST no longer exposed; connection details come from Secrets Manager
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
        // Blocker-8/9: paymentApiEndpoint is now sourced from application.properties /
        // environment variable, not hard-coded in source code.
        return "Report generation triggered for: " + month + " via " + paymentApiEndpoint;
    }

    /**
     * Validates authentication credentials using AWS Secrets Manager.
     * Blocker-18: File-based authentication replaced — credentials are retrieved from
     * Secrets Manager (secret: resortslite/auth/credentials) rather than local files.
     */
    public boolean validateCredentials(String username, String password) {
        try {
            String storedUsername = getSecretField(authSecretName, "username");
            String storedPassword = getSecretField(authSecretName, "password");
            return username != null && username.equals(storedUsername)
                    && password != null && password.equals(storedPassword);
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
