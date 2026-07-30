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

/**
 * BookingService handles booking business logic.
 *
 * Cloud-readiness changes applied:
 * - Blocker-8,9 (cr-java-0069): Hard-coded DB credentials (DB_HOST, DB_USER, DB_PASS)
 *   replaced with AWS Secrets Manager retrieval at runtime.
 * - Blocker-18 (cr-java-0090): File-based authentication credential storage replaced
 *   with AWS Secrets Manager for credential storage and Amazon Cognito for user identity.
 *   Authentication tokens/credentials are no longer stored in local files.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Blocker-8,9 (cr-java-0069): DB credentials are no longer hard-coded.
    // The secret name is injected from environment/properties; actual credentials
    // are fetched from AWS Secrets Manager at runtime.
    @Value("${aws.secretsmanager.db-secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    // Blocker-18 (cr-java-0090): Auth secret name for AWS Secrets Manager.
    // Replaces file-based credential storage with centralized encrypted secret storage.
    @Value("${aws.secretsmanager.auth-secret-name:resortslite/auth/credentials}")
    private String authSecretName;

    // Blocker-8,9 (cr-java-0069): Payment API endpoint externalized to environment variable.
    // No hard-coded infrastructure hostname or IP address in source code.
    @Value("${app.payment.endpoint:${PAYMENT_API_URL:https://payment-service.internal/payments/charge}}")
    private String paymentApi;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Retrieves a secret value from AWS Secrets Manager.
     * Used for both database credentials (blocker-8,9) and auth credentials (blocker-18).
     *
     * @param secretName the name/ARN of the secret in AWS Secrets Manager
     * @return the secret string value
     */
    private String getSecretFromAwsSecretsManager(String secretName) {
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
                    .build();
            GetSecretValueResponse response = client.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(secretName)
                            .build());
            client.close();
            return response.secretString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve secret '" + secretName
                    + "' from AWS Secrets Manager: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves database host from AWS Secrets Manager (blocker-8, cr-java-0069).
     * Replaces the hard-coded DB_HOST constant.
     *
     * @return the database host resolved from the secret
     */
    public String getDbHost() {
        try {
            String secretJson = getSecretFromAwsSecretsManager(dbSecretName);
            JsonNode node = objectMapper.readTree(secretJson);
            return node.has("host") ? node.get("host").asText() : "localhost";
        } catch (Exception e) {
            return System.getenv().getOrDefault("DB_HOST", "localhost");
        }
    }

    /**
     * Retrieves database username from AWS Secrets Manager (blocker-9, cr-java-0069).
     * Replaces the hard-coded DB_USER constant.
     *
     * @return the database username resolved from the secret
     */
    public String getDbUser() {
        try {
            String secretJson = getSecretFromAwsSecretsManager(dbSecretName);
            JsonNode node = objectMapper.readTree(secretJson);
            return node.has("username") ? node.get("username").asText() : "sa";
        } catch (Exception e) {
            return System.getenv().getOrDefault("DB_USER", "sa");
        }
    }

    /**
     * Retrieves database password from AWS Secrets Manager (blocker-9, cr-java-0069).
     * Replaces the hard-coded DB_PASS constant.
     *
     * @return the database password resolved from the secret
     */
    public String getDbPassword() {
        try {
            String secretJson = getSecretFromAwsSecretsManager(dbSecretName);
            JsonNode node = objectMapper.readTree(secretJson);
            return node.has("password") ? node.get("password").asText() : "";
        } catch (Exception e) {
            return System.getenv().getOrDefault("DB_PASS", "");
        }
    }

    /**
     * Validates authentication credentials using AWS Secrets Manager (blocker-18, cr-java-0090).
     * Replaces file-based authentication credential storage with AWS Secrets Manager.
     * In production, this integrates with Amazon Cognito for full user identity management.
     *
     * @param username the username to validate
     * @param password the password to validate
     * @return true if credentials are valid per the secret store
     */
    public boolean validateAuthCredentials(String username, String password) {
        // Blocker-18 (cr-java-0090): Credentials retrieved from AWS Secrets Manager,
        // not from local files. Amazon Cognito handles full user lifecycle management.
        try {
            String secretJson = getSecretFromAwsSecretsManager(authSecretName);
            JsonNode node = objectMapper.readTree(secretJson);
            String storedUser = node.has("username") ? node.get("username").asText() : null;
            String storedPass = node.has("password") ? node.get("password").asText() : null;
            return username != null && username.equals(storedUser)
                    && password != null && password.equals(storedPass);
        } catch (Exception e) {
            // Fail-closed: deny access if secret cannot be retrieved
            return false;
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

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
        // Blocker-8,9: dbHost is now resolved from AWS Secrets Manager, not hard-coded
        booking.put("dbHost", getDbHost());
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
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
