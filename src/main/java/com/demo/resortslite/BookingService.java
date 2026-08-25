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

    // Blocker-8,9 (cr-java-0069): Hard-coded database credentials removed from source code.
    // Credentials are now retrieved at runtime from AWS Secrets Manager.
    // The secret name is injected via environment variable — no credentials in source.
    @Value("${AWS_DB_SECRET_NAME:resortslite/db/credentials}")
    private String dbSecretName;

    // Blocker-18 (cr-java-0090): File-based authentication replaced with AWS Secrets Manager.
    // Authentication credentials are stored in Secrets Manager, not local files.
    @Value("${AWS_AUTH_SECRET_NAME:resortslite/auth/credentials}")
    private String authSecretName;

    // Blocker-10 (cr-java-0071): Hard-coded payment API URL removed.
    // Endpoint is injected from environment variable / SSM Parameter Store.
    @Value("${PAYMENT_API_URL:#{null}}")
    private String paymentApiUrl;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    public BookingService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * Blocker-8,9 (cr-java-0069): Replaces hard-coded DB_USER / DB_PASS / DB_HOST constants.
     *
     * @return map containing db host, username, and password from Secrets Manager
     */
    public Map<String, String> getDatabaseCredentials() {
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
            credentials.put("error", "Failed to retrieve DB credentials: " + e.getMessage());
        }
        return credentials;
    }

    /**
     * Retrieves authentication credentials from AWS Secrets Manager.
     * Blocker-18 (cr-java-0090): Replaces file-based authentication with AWS Secrets Manager.
     * Credentials are centralized, encrypted, and auditable — no local file dependency.
     *
     * @return map containing auth credentials from Secrets Manager
     */
    public Map<String, String> getAuthCredentials() {
        Map<String, String> authCredentials = new HashMap<>();
        try {
            GetSecretValueResponse secretValue = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(authSecretName)
                            .build());
            JsonNode secretJson = objectMapper.readTree(secretValue.secretString());
            authCredentials.put("clientId", secretJson.path("clientId").asText());
            authCredentials.put("clientSecret", secretJson.path("clientSecret").asText());
            authCredentials.put("cognitoUserPoolId", secretJson.path("cognitoUserPoolId").asText());
        } catch (Exception e) {
            authCredentials.put("error", "Failed to retrieve auth credentials: " + e.getMessage());
        }
        return authCredentials;
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterized query prevents SQL injection
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // Blocker-8,9: DB host no longer exposed in response — retrieved securely from Secrets Manager
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterized query prevents SQL injection
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
        // Blocker-8,9: paymentApiUrl now sourced from environment variable, not hard-coded
        return "Report generation triggered for: " + month + " via " + paymentApiUrl;
    }

    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
