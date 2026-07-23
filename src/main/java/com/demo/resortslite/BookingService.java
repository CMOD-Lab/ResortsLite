package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * BookingService handles all booking-related business logic.
 *
 * <p>Database credentials are retrieved at runtime from AWS Secrets Manager
 * (secret name controlled by the DB_SECRET_NAME environment variable) instead of
 * being hard-coded in source code. The payment API endpoint is externalised to an
 * environment variable (PAYMENT_API_URL) so it can be changed per environment without
 * a code change or redeployment.</p>
 *
 * <p>Authentication credentials (previously stored in local files) are now managed
 * through AWS Secrets Manager, providing centralized, encrypted, and auditable
 * credential storage with built-in rotation support.</p>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // AWS Secrets Manager client — thread-safe and reusable across requests.
    private final SecretsManagerClient secretsManagerClient = SecretsManagerClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Secret name is injected via environment variable DB_SECRET_NAME.
    // In AWS, set this to the ARN or name of the Secrets Manager secret that holds
    // { "host": "...", "username": "...", "password": "..." }.
    private final String dbSecretName = System.getenv().getOrDefault(
            "DB_SECRET_NAME", "resorts/db/credentials");

    // Payment API endpoint is externalised to an environment variable.
    // Set PAYMENT_API_URL in the ECS task definition / Elastic Beanstalk environment.
    private final String paymentApi = System.getenv().getOrDefault(
            "PAYMENT_API_URL", "https://payment-svc.internal/payments/charge");

    /**
     * Retrieves the database host from AWS Secrets Manager.
     * Credentials are never stored in source code or compiled binaries.
     *
     * @return the database host string, or an empty string on failure
     */
    private String getDbHostFromSecret() {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            JsonNode secretJson = objectMapper.readTree(response.secretString());
            return secretJson.path("host").asText("");
        } catch (Exception e) {
            // Log and return empty — caller handles missing host gracefully.
            return "";
        }
    }

    /**
     * Retrieves the database username from AWS Secrets Manager.
     *
     * @return the database username, or an empty string on failure
     */
    private String getDbUserFromSecret() {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            JsonNode secretJson = objectMapper.readTree(response.secretString());
            return secretJson.path("username").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Creates a new booking record in the database.
     *
     * @param guestName the guest's full name
     * @param roomType  the room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn   check-in date string
     * @param checkOut  check-out date string
     * @return a map containing the booking details and confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // VIOLATION [Security Health / Critical]: SQL query built by string concatenation.
        // An attacker can pass guestName = "'; DROP TABLE bookings; --" to destroy data.
        // Use parameterised queries (JdbcTemplate with '?') to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('" // sql-inject-001
                + bookingId + "', '" + guestName + "', '" + roomType               // sql-inject-001
                + "', '" + checkIn + "', '" + checkOut + "')";                     // sql-inject-001
        jdbcTemplate.execute(sql);

        // VIOLATION [Security Health / High]: MD5 is a broken hash algorithm (RFC 6151).
        // Do not use MD5 for any security-related hashing. Use SHA-256 or bcrypt.
        String confirmCode = md5Hash(bookingId + guestName); // sec-weak-hash-001

        // Retrieve DB host from Secrets Manager at runtime (not hard-coded).
        String dbHost = getDbHostFromSecret();

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
     * Retrieves a booking record by its identifier.
     *
     * @param bookingId the unique booking identifier
     * @return a map of booking fields, or an error entry if not found
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        // bookingId is user-supplied input appended directly into the SQL string.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // VIOLATION [Code Sustainability / High]: High cyclomatic complexity.
    // This method has 9+ decision branches. Automated transformation tools flag methods
    // above complexity threshold as high maintenance risk and transformation blockers.
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
        // VIOLATION [Code Sustainability / Medium]: Duplicated validation logic.
        // Same room type validation is repeated here and in calculateRoomPrice.
        // Should be extracted to a shared RoomType enum or validator.
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE") // dup-logic-001
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) { // dup-logic-001
            return false;
        }
        return true;
    }

    /**
     * Generates a report summary string using the externalised payment API endpoint.
     *
     * @param month the month for which the report is generated
     * @return a descriptive report trigger message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Validates user credentials against AWS Secrets Manager.
     *
     * <p>Authentication credentials are retrieved from AWS Secrets Manager
     * (secret name: resorts/auth/credentials) rather than from local files,
     * providing centralized, encrypted, and auditable authentication with
     * built-in user lifecycle management via Amazon Cognito integration.</p>
     *
     * @param username the username to validate
     * @param password the password to validate
     * @return true if credentials are valid, false otherwise
     */
    public boolean validateUserCredentials(String username, String password) {
        try {
            String authSecretName = System.getenv().getOrDefault(
                    "AUTH_SECRET_NAME", "resorts/auth/credentials");
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(authSecretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            JsonNode secretJson = objectMapper.readTree(response.secretString());
            String storedUser = secretJson.path("username").asText("");
            String storedPass = secretJson.path("password").asText("");
            return storedUser.equals(username) && storedPass.equals(password);
        } catch (Exception e) {
            return false;
        }
    }

    private String md5Hash(String input) { // sec-weak-hash-001
        try {
            MessageDigest md = MessageDigest.getInstance("MD5"); // sec-weak-hash-001
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
