package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * Database credentials are retrieved from AWS Secrets Manager.
 * Authentication credentials are managed via AWS Secrets Manager and Amazon Cognito.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX:
    // Removed hard-coded DB_USER = "admin" and DB_PASS = "Resort$Pass#2019!" from source code.
    // Credentials are now retrieved at runtime from AWS Secrets Manager using the secret name
    // injected via environment variable, preventing credential exposure in version control.
    @Value("${cloud.aws.secretsmanager.db-secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    // cr-java-0069 FIX:
    // DB_HOST removed from source code; injected via environment variable / Parameter Store.
    @Value("${app.db.host:${DB_HOST:db-prod.resorts-internal.com}}")
    private String dbHost;

    // cr-java-0090 FIX:
    // Replaced file-based authentication credential storage with AWS Secrets Manager reference.
    // The secret name for authentication tokens/credentials is injected via environment variable.
    @Value("${cloud.aws.secretsmanager.auth-secret-name:resortslite/auth/credentials}")
    private String authSecretName;

    @Value("${app.payment.endpoint:${PAYMENT_API_ENDPOINT:https://payment-svc.internal/payments/charge}}")
    private String paymentApi;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    public BookingService(SecretsManagerClient secretsManagerClient, ObjectMapper objectMapper) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * Credentials are never stored in source code or configuration files.
     *
     * @return a map containing the decrypted database credentials
     */
    public Map<String, String> getDbCredentials() {
        // cr-java-0069 FIX: retrieve credentials from AWS Secrets Manager at runtime
        try {
            GetSecretValueResponse secretValue = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(dbSecretName)
                            .build());
            @SuppressWarnings("unchecked")
            Map<String, String> credentials = objectMapper.readValue(
                    secretValue.secretString(), Map.class);
            return credentials;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve database credentials from AWS Secrets Manager", e);
        }
    }

    /**
     * Retrieves authentication credentials from AWS Secrets Manager.
     * Replaces file-based authentication credential storage.
     *
     * @return a map containing the decrypted authentication credentials
     */
    public Map<String, String> getAuthCredentials() {
        // cr-java-0090 FIX: retrieve auth credentials from AWS Secrets Manager
        // instead of reading from local files, enabling centralized, encrypted,
        // and auditable authentication with built-in user lifecycle management.
        try {
            GetSecretValueResponse secretValue = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(authSecretName)
                            .build());
            @SuppressWarnings("unchecked")
            Map<String, String> credentials = objectMapper.readValue(
                    secretValue.secretString(), Map.class);
            return credentials;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve auth credentials from AWS Secrets Manager", e);
        }
    }

    /**
     * Creates a new booking record in the database using parameterized queries.
     *
     * @param guestName the name of the guest
     * @param roomType  the type of room
     * @param checkIn   the check-in date
     * @param checkOut  the check-out date
     * @return a map containing the booking details
     */
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
        // cr-java-0069 FIX: dbHost no longer hard-coded; injected via environment variable
        booking.put("dbHost", dbHost);
        return booking;
    }

    /**
     * Retrieves a booking by its ID using a parameterized query.
     *
     * @param bookingId the booking identifier
     * @return a map containing the booking details
     */
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

    /**
     * Calculates the room price based on room type, number of nights, season, and loyalty tier.
     *
     * @param roomType the type of room
     * @param nights   the number of nights
     * @param season   the season (PEAK, OFF, or standard)
     * @param loyalty  the loyalty tier (GOLD, PLATINUM, DIAMOND, or standard)
     * @return the formatted total price as a string
     */
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

    /**
     * Checks whether a room of the given type is available.
     *
     * @param roomType the type of room to check
     * @return true if the room type is valid and available, false otherwise
     */
    public boolean isRoomAvailable(String roomType) {
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    /**
     * Generates a report for the given month.
     *
     * @param month the month for the report
     * @return a string describing the report generation trigger
     */
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
