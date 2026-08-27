package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService handles resort booking operations.
 *
 * <p>Cloud-readiness changes applied:
 * <ul>
 *   <li>Hard-coded database credentials (DB_USER / DB_PASS) replaced with
 *       AWS Secrets Manager lookups (blockers 8, 9 / cr-java-0069).</li>
 *   <li>Hard-coded DB_HOST and PAYMENT_API URL replaced with environment
 *       variable injection via @Value (cr-java-0071).</li>
 *   <li>File-based authentication replaced with AWS Secrets Manager for
 *       credential storage (blocker 18 / cr-java-0090).</li>
 * </ul>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final SecretsManagerClient secretsManagerClient;

    /**
     * AWS Secrets Manager secret name that stores the database credentials JSON.
     * Replaces hard-coded DB_USER / DB_PASS constants (blockers 8, 9).
     * Set via environment variable DB_SECRET_NAME or application property.
     */
    @Value("${aws.secretsmanager.db-secret-name:${DB_SECRET_NAME:resortslite/db/credentials}}")
    private String dbSecretName;

    /**
     * AWS Secrets Manager secret name for authentication credentials (blocker 18).
     * Replaces file-based credential storage.
     */
    @Value("${aws.secretsmanager.auth-secret-name:${AUTH_SECRET_NAME:resortslite/auth/credentials}}")
    private String authSecretName;

    /**
     * Database host injected from environment variable DB_HOST.
     * Replaces hard-coded "db-prod.resorts-internal.com".
     */
    @Value("${app.db.host:${DB_HOST:localhost}}")
    private String dbHost;

    /**
     * Payment API endpoint injected from environment variable PAYMENT_API_URL.
     * Replaces hard-coded "http://10.0.1.45:9090/payments/charge".
     */
    @Value("${app.payment.endpoint:${PAYMENT_API_URL:https://payment-svc.internal/payments/charge}}")
    private String paymentApi;

    public BookingService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
    }

    /**
     * Retrieves the database credentials JSON string from AWS Secrets Manager.
     * This replaces the hard-coded DB_USER and DB_PASS constants.
     *
     * @return JSON string containing database credentials
     */
    public String getDbCredentials() {
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(dbSecretName)
                            .build());
            return response.secretString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve database credentials from AWS Secrets Manager: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Retrieves authentication credentials from AWS Secrets Manager.
     * Replaces file-based authentication (blocker 18 / cr-java-0090).
     *
     * @param credentialKey the specific credential key to retrieve
     * @return the credential value from Secrets Manager
     */
    public String getAuthCredential(String credentialKey) {
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(authSecretName)
                            .build());
            // Parse the JSON secret string to extract the specific credential
            String secretJson = response.secretString();
            // Extract value for the given key from the JSON secret
            String searchKey = "\"" + credentialKey + "\"";
            int keyIndex = secretJson.indexOf(searchKey);
            if (keyIndex >= 0) {
                int colonIndex = secretJson.indexOf(":", keyIndex);
                int valueStart = secretJson.indexOf("\"", colonIndex) + 1;
                int valueEnd = secretJson.indexOf("\"", valueStart);
                return secretJson.substring(valueStart, valueEnd);
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve auth credentials from AWS Secrets Manager: "
                    + e.getMessage(), e);
        }
    }

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

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
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
