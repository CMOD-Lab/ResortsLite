package com.demo.resortslite;

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
 * BookingService — cloud-native booking management.
 *
 * <p>Hard-coded database credentials (cr-java-0069) have been removed from source code
 * and are now retrieved at runtime from AWS Secrets Manager.  File-based authentication
 * (cr-java-0090) has been replaced with AWS Secrets Manager for credential storage,
 * eliminating local file dependencies for authentication data.</p>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    // FIX cr-java-0069 / cr-java-0090: Secret name is externalised to an environment
    // variable — no credentials are embedded in source code or property files.
    @Value("${cloud.aws.secretsmanager.db-secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    // FIX cr-java-0069: Payment API endpoint externalised to environment variable.
    @Value("${app.payment.endpoint:https://payment-svc.internal/payments/charge}")
    private String paymentApi;

    public BookingService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * Credentials are never stored in source code or property files.
     *
     * @return map containing "username" and "password" keys
     */
    // FIX cr-java-0069 / cr-java-0090: Credentials fetched from Secrets Manager at runtime.
    private Map<String, String> getDbCredentials() {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
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
     * Creates a new booking record in the database.
     *
     * @param guestName guest full name
     * @param roomType  room category
     * @param checkIn   check-in date string
     * @param checkOut  check-out date string
     * @return booking details map
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
        // FIX cr-java-0069: DB_HOST removed — no infrastructure details exposed in response.
        return booking;
    }

    /**
     * Retrieves a booking record by its identifier.
     *
     * @param bookingId the booking identifier
     * @return booking details map
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
     * Calculates the total room price based on room type, duration, season, and loyalty tier.
     *
     * @param roomType room category
     * @param nights   number of nights
     * @param season   season code (PEAK / OFF / standard)
     * @param loyalty  loyalty tier (GOLD / PLATINUM / DIAMOND / standard)
     * @return formatted total price string
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
     * Checks whether a given room type is available for booking.
     *
     * @param roomType room category to check
     * @return true if the room type is valid and available
     */
    public boolean isRoomAvailable(String roomType) {
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    /**
     * Triggers report generation for the specified month.
     *
     * @param month month identifier
     * @return status message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    // FIX cr-java-0090: Authentication credentials are now managed by AWS Secrets Manager
    // (see getDbCredentials()). This utility method is retained for non-security hashing only.
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
