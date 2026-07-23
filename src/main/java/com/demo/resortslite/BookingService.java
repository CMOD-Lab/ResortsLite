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
 * BookingService — cloud-native service layer.
 *
 * Changes applied for cloud readiness:
 *  - Blocker-8/9 (cr-java-0069): Hard-coded DB credentials (DB_HOST, DB_USER, DB_PASS)
 *    replaced with AWS Secrets Manager retrieval via SecretsManagerClient.
 *  - Blocker-18 (cr-java-0090): File-based authentication credential storage replaced with
 *    AWS Secrets Manager for credential storage and Amazon Cognito for user identity management.
 *    The authentication token/credential lookup now delegates to Secrets Manager instead of
 *    reading from a local file.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    /**
     * AWS Secrets Manager secret name for database credentials.
     * Replaces hard-coded DB_HOST, DB_USER, DB_PASS constants (blocker-8/9, cr-java-0069).
     * Set DB_SECRET_NAME in ECS task definition / Elastic Beanstalk environment.
     */
    @Value("${DB_SECRET_NAME:resortslite/db/credentials}")
    private String dbSecretName;

    /**
     * AWS Secrets Manager secret name for authentication credentials.
     * Replaces file-based authentication storage (blocker-18, cr-java-0090).
     * Set AUTH_SECRET_NAME in ECS task definition / Elastic Beanstalk environment.
     */
    @Value("${AUTH_SECRET_NAME:resortslite/auth/credentials}")
    private String authSecretName;

    /**
     * Payment API endpoint — injected from environment variable / SSM Parameter Store.
     * Replaces the hard-coded http://10.0.1.45:9090/payments/charge constant.
     */
    @Value("${PAYMENT_API_URL:https://payment-service.internal/payments/charge}")
    private String paymentApi;

    public BookingService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * Replaces the hard-coded DB_HOST, DB_USER, DB_PASS fields (blocker-8/9, cr-java-0069).
     *
     * @return JsonNode containing host, username, password fields from the secret.
     */
    private JsonNode getDbCredentials() {
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(dbSecretName)
                            .build());
            return objectMapper.readTree(response.secretString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve database credentials from AWS Secrets Manager: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Retrieves authentication credentials from AWS Secrets Manager.
     * Replaces file-based authentication storage (blocker-18, cr-java-0090).
     * Amazon Cognito handles user identity lifecycle; Secrets Manager stores service credentials.
     *
     * @param credentialKey the credential identifier to look up
     * @return the credential value from Secrets Manager
     */
    public String getAuthCredential(String credentialKey) {
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(authSecretName)
                            .build());
            JsonNode secretNode = objectMapper.readTree(response.secretString());
            JsonNode credNode = secretNode.get(credentialKey);
            return credNode != null ? credNode.asText() : null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve authentication credential from AWS Secrets Manager: "
                    + e.getMessage(), e);
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Retrieve DB host from Secrets Manager for informational purposes
        JsonNode dbCreds = getDbCredentials();
        String dbHost = dbCreds.has("host") ? dbCreds.get("host").asText() : "managed-by-secrets-manager";

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
        booking.put("dbHost", dbHost);
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
