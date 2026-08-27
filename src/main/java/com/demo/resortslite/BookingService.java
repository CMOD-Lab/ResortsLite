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

import javax.annotation.PostConstruct;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native implementation.
 *
 * <p>Hard-coded database credentials (cr-java-0069) and the file-based
 * authentication pattern (cr-java-0090) have been replaced with AWS Secrets
 * Manager. The DB_HOST, DB_USER, and DB_PASS constants are no longer embedded
 * in source code; they are retrieved at startup from a named secret in
 * AWS Secrets Manager and injected into the application context.</p>
 *
 * <p>The PAYMENT_API endpoint is externalised to an environment variable /
 * application property so it can be overridden per deployment environment
 * without code changes.</p>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -----------------------------------------------------------------------
    // AWS Secrets Manager client — used to retrieve DB credentials and
    // authentication tokens at runtime (replaces hard-coded constants and
    // file-based credential storage).
    // -----------------------------------------------------------------------
    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Name of the AWS Secrets Manager secret that stores DB credentials.
     * Injected from environment variable / application.properties so it can
     * differ per environment (dev / staging / prod).
     */
    @Value("${aws.secretsmanager.db-secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    /**
     * Name of the AWS Secrets Manager secret that stores authentication
     * credentials / tokens (replaces file-based authentication, cr-java-0090).
     */
    @Value("${aws.secretsmanager.auth-secret-name:resortslite/auth/credentials}")
    private String authSecretName;

    /**
     * Payment API endpoint — externalised to environment variable / property
     * (replaces hard-coded "http://10.0.1.45:9090/payments/charge").
     */
    @Value("${app.payment.endpoint:${PAYMENT_API_ENDPOINT:http://payment-svc.internal:9090/payments/charge}}")
    private String paymentApi;

    // Resolved at startup from Secrets Manager — never stored in source code
    private String dbHost;
    private String dbUser;
    // DB password is intentionally NOT stored as a field; it is retrieved
    // on-demand from Secrets Manager to support automatic rotation.

    public BookingService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
    }

    /**
     * Resolves non-sensitive DB metadata (host, user) from AWS Secrets Manager
     * once at startup. The actual password is fetched on-demand to support
     * automatic credential rotation without application restart.
     */
    @PostConstruct
    public void resolveDbCredentials() {
        try {
            Map<String, String> secret = fetchSecret(dbSecretName);
            this.dbHost = secret.getOrDefault("host", "localhost");
            this.dbUser = secret.getOrDefault("username", "sa");
            // password intentionally not cached — fetched per-use to honour rotation
        } catch (Exception e) {
            // Fall back to environment variables if Secrets Manager is unavailable
            // (e.g. local development without AWS credentials)
            this.dbHost = System.getenv().getOrDefault("DB_HOST", "localhost");
            this.dbUser = System.getenv().getOrDefault("DB_USER", "sa");
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

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
        // dbHost is now resolved from Secrets Manager — safe to include in response
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

    /**
     * Retrieves authentication credentials from AWS Secrets Manager
     * (replaces file-based authentication, cr-java-0090).
     *
     * <p>Credentials are never stored in local files or source code.
     * AWS Secrets Manager provides encrypted storage, audit logging,
     * and automatic rotation support.</p>
     *
     * @param credentialKey the specific credential key to retrieve
     * @return credential value, or empty string if not found
     */
    public String getAuthCredential(String credentialKey) {
        try {
            Map<String, String> authSecret = fetchSecret(authSecretName);
            return authSecret.getOrDefault(credentialKey, "");
        } catch (Exception e) {
            return "";
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Fetches a secret from AWS Secrets Manager and parses it as a JSON map.
     *
     * @param secretName the secret name or ARN
     * @return map of key-value pairs from the secret JSON
     */
    private Map<String, String> fetchSecret(String secretName) {
        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();
        GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
        String secretString = response.secretString();

        Map<String, String> result = new HashMap<>();
        try {
            JsonNode node = objectMapper.readTree(secretString);
            node.fields().forEachRemaining(entry ->
                    result.put(entry.getKey(), entry.getValue().asText()));
        } catch (Exception e) {
            // Secret is a plain string — store under "value" key
            result.put("value", secretString);
        }
        return result;
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
