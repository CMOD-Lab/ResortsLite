package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService handles resort booking operations.
 * Database credentials are retrieved from AWS Secrets Manager (blockers 8, 9, 18)
 * rather than being hard-coded in source code, enabling secure credential rotation
 * without redeployment.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    // Secret name in AWS Secrets Manager — externalized, not hard-coded credentials
    private static final String DB_SECRET_NAME = System.getenv()
            .getOrDefault("DB_SECRET_NAME", "resortslite/db/credentials");

    // Authentication secret name in AWS Secrets Manager (replaces blocker-18 file-based auth)
    private static final String AUTH_SECRET_NAME = System.getenv()
            .getOrDefault("AUTH_SECRET_NAME", "resortslite/auth/credentials");

    // Payment API endpoint externalized via environment variable — no hard-coded IP/hostname
    private static final String PAYMENT_API = System.getenv()
            .getOrDefault("PAYMENT_API_URL", "https://payment-service/payments/charge");

    public BookingService() {
        this.secretsManagerClient = SecretsManagerClient.create();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * Replaces hard-coded DB_USER and DB_PASS constants (blockers 8, 9).
     *
     * @return map containing "username" and "password" keys
     */
    public Map<String, String> getDatabaseCredentials() {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(DB_SECRET_NAME)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            @SuppressWarnings("unchecked")
            Map<String, String> credentials = objectMapper.readValue(
                    response.secretString(), Map.class);
            return credentials;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve database credentials from AWS Secrets Manager: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Retrieves authentication credentials from AWS Secrets Manager.
     * Replaces file-based authentication storage (blocker-18).
     *
     * @return map containing authentication configuration
     */
    public Map<String, String> getAuthCredentials() {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(AUTH_SECRET_NAME)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            @SuppressWarnings("unchecked")
            Map<String, String> authConfig = objectMapper.readValue(
                    response.secretString(), Map.class);
            return authConfig;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve auth credentials from AWS Secrets Manager: "
                    + e.getMessage(), e);
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterized query — prevents SQL injection
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
        // DB_HOST removed — credentials managed by AWS Secrets Manager
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterized query — prevents SQL injection
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
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
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
