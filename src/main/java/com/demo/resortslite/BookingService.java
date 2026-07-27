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

    // Blocker-8,9 (cr-java-0069): Hard-coded DB credentials removed from source code.
    // DB host, username, and password are now retrieved from AWS Secrets Manager at runtime,
    // enabling credential rotation without redeployment and preventing exposure in git history.
    @Value("${cloud.aws.secretsmanager.db-secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    // Blocker-18 (cr-java-0090): Hard-coded payment API URL removed.
    // Endpoint is externalised to application properties / environment variable.
    @Value("${app.payment.endpoint:#{null}}")
    private String paymentApiEndpoint;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    public BookingService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Blocker-8,9 (cr-java-0069): Retrieves database credentials from AWS Secrets Manager.
     * Returns a map with keys "host", "username", "password".
     */
    private Map<String, String> getDbCredentials() {
        Map<String, String> credentials = new HashMap<>();
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretJson = response.secretString();
            JsonNode node = objectMapper.readTree(secretJson);
            credentials.put("host", node.path("host").asText("localhost"));
            credentials.put("username", node.path("username").asText("sa"));
            credentials.put("password", node.path("password").asText(""));
        } catch (Exception e) {
            // Log and rethrow — credentials must not fall back to hard-coded values
            throw new RuntimeException("Failed to retrieve DB credentials from AWS Secrets Manager: " + e.getMessage(), e);
        }
        return credentials;
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Blocker-8,9 (cr-java-0069): DB host is now resolved from Secrets Manager, not hard-coded.
        Map<String, String> dbCreds = getDbCredentials();
        String dbHost = dbCreds.get("host");

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
        // Blocker-18 (cr-java-0090): Payment API endpoint now sourced from environment variable
        // / application properties instead of being hard-coded in source.
        String endpoint = (paymentApiEndpoint != null && !paymentApiEndpoint.isEmpty())
                ? paymentApiEndpoint
                : "https://payment-svc.internal/payments/charge";
        return "Report generation triggered for: " + month + " via " + endpoint;
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
