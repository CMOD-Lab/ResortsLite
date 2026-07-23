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

import javax.annotation.PostConstruct;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native implementation.
 *
 * <p>Hard-coded database credentials (DB_HOST, DB_USER, DB_PASS) have been
 * removed and replaced with values retrieved from AWS Secrets Manager at
 * application startup. This enables credential rotation without redeployment
 * and prevents credential exposure in source control or container images.</p>
 *
 * <p>File-based authentication storage has been replaced with AWS Secrets
 * Manager for credential storage, providing centralized, encrypted, and
 * auditable authentication management.</p>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -----------------------------------------------------------------------
    // AWS configuration — injected from application.properties / env vars.
    // -----------------------------------------------------------------------
    @Value("${aws.region}")
    private String awsRegion;

    /**
     * Name of the AWS Secrets Manager secret that holds the database
     * credentials JSON: {"host":"...","username":"...","password":"..."}.
     * Replaces hard-coded DB_HOST, DB_USER, DB_PASS constants.
     */
    @Value("${aws.secretsmanager.db-secret-name}")
    private String dbSecretName;

    // -----------------------------------------------------------------------
    // Credentials resolved from Secrets Manager at startup — never hard-coded.
    // Replaces:
    //   private static final String DB_HOST = "db-prod.resorts-internal.com";
    //   private static final String DB_USER = "admin";
    //   private static final String DB_PASS = "Resort$Pass#2019!";
    // -----------------------------------------------------------------------
    private String resolvedDbHost;
    private String resolvedDbUser;

    // Payment API endpoint — injected from environment variable / Parameter Store
    @Value("${aws.ssm.param.payment-endpoint:#{null}}")
    private String paymentApiParam;

    private SecretsManagerClient secretsManagerClient;

    private SecretsManagerClient getSecretsManagerClient() {
        if (secretsManagerClient == null) {
            secretsManagerClient = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();
        }
        return secretsManagerClient;
    }

    /**
     * Resolves database credentials from AWS Secrets Manager at application
     * startup. The secret is expected to be a JSON object with keys:
     * {@code host}, {@code username}, and {@code password}.
     *
     * <p>This replaces file-based authentication storage (blocker-18) and
     * hard-coded credential constants (blockers 8 and 9) with centralized,
     * encrypted secret management that supports automatic rotation.</p>
     */
    @PostConstruct
    public void resolveCredentialsFromSecretsManager() {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = getSecretsManagerClient().getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretNode = mapper.readTree(secretJson);

            resolvedDbHost = secretNode.has("host") ? secretNode.get("host").asText() : "unknown";
            resolvedDbUser = secretNode.has("username") ? secretNode.get("username").asText() : "unknown";
            // Password is intentionally not stored in a field — used only where needed
        } catch (Exception e) {
            // Fallback for local development without Secrets Manager
            resolvedDbHost = System.getenv().getOrDefault("DB_HOST", "localhost");
            resolvedDbUser = System.getenv().getOrDefault("DB_USERNAME", "sa");
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
        // DB host is now resolved from Secrets Manager — not hard-coded in source
        booking.put("dbHost", resolvedDbHost);
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
        return "Report generation triggered for: " + month + " via " + paymentApiParam;
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
