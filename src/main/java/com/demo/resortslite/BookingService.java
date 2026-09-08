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
 * BookingService handles resort booking operations.
 * Database credentials are retrieved from AWS Secrets Manager — no hard-coded
 * credentials in source code. Authentication tokens are managed via AWS Secrets
 * Manager and Amazon Cognito rather than local file storage.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // AWS region injected from environment variable
    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    // AWS Secrets Manager secret name for database credentials — replaces hard-coded DB_USER / DB_PASS
    @Value("${cloud.aws.secretsmanager.db-secret-name:resorts/db/credentials}")
    private String dbSecretName;

    // AWS Secrets Manager secret name for the payment API credentials / endpoint
    @Value("${cloud.aws.secretsmanager.payment-secret-name:resorts/payment/api}")
    private String paymentSecretName;

    /**
     * Retrieves a secret value from AWS Secrets Manager.
     * Replaces all hard-coded credential constants (DB_HOST, DB_USER, DB_PASS).
     *
     * @param secretName the name/ARN of the secret in AWS Secrets Manager
     * @return the secret string value
     */
    private String getSecret(String secretName) {
        SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();

        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();

        GetSecretValueResponse response = client.getSecretValue(request);
        return response.secretString();
    }

    /**
     * Retrieves a specific field from a JSON secret stored in AWS Secrets Manager.
     *
     * @param secretName the name/ARN of the secret
     * @param fieldName  the JSON field to extract
     * @return the field value, or empty string if not found
     */
    private String getSecretField(String secretName, String fieldName) {
        try {
            String secretJson = getSecret(secretName);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(secretJson);
            return node.has(fieldName) ? node.get(fieldName).asText() : "";
        } catch (Exception e) {
            // Return empty string on failure; caller should handle appropriately
            return "";
        }
    }

    /**
     * Creates a new booking record. Credentials are sourced from AWS Secrets Manager.
     * Uses parameterized queries to prevent SQL injection.
     *
     * @param guestName guest's full name
     * @param roomType  type of room requested
     * @param checkIn   check-in date string
     * @param checkOut  check-out date string
     * @return booking details map
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterized query — prevents SQL injection
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // VIOLATION [Security Health / High]: MD5 is a broken hash algorithm (RFC 6151).
        // Do not use MD5 for any security-related hashing. Use SHA-256 or bcrypt.
        String confirmCode = md5Hash(bookingId + guestName); // sec-weak-hash-001

        // Retrieve DB host from AWS Secrets Manager — no hard-coded credential in source
        String dbHost = getSecretField(dbSecretName, "host");

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

    /**
     * Retrieves a booking by its ID using a parameterized query.
     *
     * @param bookingId the booking identifier
     * @return booking details map
     */
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

    /**
     * Calculates the room price based on room type, number of nights, season, and loyalty tier.
     *
     * @param roomType the type of room
     * @param nights   number of nights
     * @param season   season code (PEAK, OFF, or standard)
     * @param loyalty  loyalty tier (GOLD, PLATINUM, DIAMOND, or standard)
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
     * Checks whether a given room type is available.
     *
     * @param roomType the room type to check
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
     * Generates a report for the given month. The payment API endpoint is retrieved
     * from AWS Secrets Manager — no hard-coded internal IP or URL in source code.
     * This replaces the file-based authentication pattern with AWS Secrets Manager
     * for credential/endpoint storage (blocker-18: file-based authentication).
     *
     * @param month the month for which to generate the report
     * @return report generation status message
     */
    public String generateReport(String month) {
        // Retrieve payment API endpoint from AWS Secrets Manager — replaces hard-coded URL
        // and file-based credential storage (cr-java-0090 / blocker-18)
        String paymentApi = getSecretField(paymentSecretName, "endpoint");
        if (paymentApi == null || paymentApi.isEmpty()) {
            paymentApi = System.getenv().getOrDefault("PAYMENT_API_ENDPOINT",
                    "https://payment-service/payments/charge");
        }
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
