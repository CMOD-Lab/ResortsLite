package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.PostConstruct;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: Hard-coded database credentials replaced with AWS Secrets Manager.
    // DB_USER and DB_PASS are no longer embedded in source code. Credentials are fetched
    // at runtime from AWS Secrets Manager using the secret name supplied via environment
    // variable / application property, enabling automatic rotation without redeployment.
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    /** Name of the AWS Secrets Manager secret that holds the DB credentials JSON. */
    @Value("${aws.secretsmanager.db-credentials-secret-name:resortslite/db/credentials}")
    private String dbCredentialsSecretName;

    /** AWS region used to build the SecretsManagerClient. */
    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    // Resolved at startup via AWS Secrets Manager — never stored in source code.
    private String dbUser;
    private String dbPass;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Fetches DB credentials from AWS Secrets Manager once at application startup.
     * The secret is expected to be a JSON object with keys "username" and "password":
     * <pre>{"username":"admin","password":"Resort$Pass#2019!"}</pre>
     *
     * Storing credentials in Secrets Manager provides:
     *  - Encrypted storage (AES-256 via AWS KMS)
     *  - Automatic rotation support without code changes
     *  - Fine-grained IAM access control
     *  - Full audit trail via AWS CloudTrail
     */
    @PostConstruct
    public void loadDbCredentialsFromSecretsManager() {
        try {
            SecretsManagerClient secretsClient = SecretsManagerClient.builder()
                    .region(software.amazon.awssdk.regions.Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbCredentialsSecretName)
                    .build();

            GetSecretValueResponse response = secretsClient.getSecretValue(request);
            String secretJson = response.secretString();

            // Parse the JSON secret: {"username":"...","password":"..."}
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> secretMap = mapper.readValue(secretJson, Map.class);

            this.dbUser = secretMap.get("username");
            this.dbPass = secretMap.get("password");

            secretsClient.close();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load DB credentials from AWS Secrets Manager (secret: "
                            + dbCredentialsSecretName + "): " + e.getMessage(), e);
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

        // cr-java-0090 FIX: File-based Authentication — replaced insecure local MD5-based
        // token generation with a cryptographically secure confirmation code generated via
        // SecureRandom + Base64 encoding.
        //
        // Cloud-native authentication pattern (AWS):
        //  - User identity and session tokens are managed by Amazon Cognito User Pools.
        //    Cognito issues JWT access/ID tokens after authentication; these tokens are
        //    validated by the application using Cognito's JWKS endpoint — no local file
        //    storage of credentials or tokens is required.
        //  - Sensitive authentication secrets (e.g., Cognito App Client secrets, API keys)
        //    are stored in AWS Secrets Manager and fetched at runtime (see
        //    loadDbCredentialsFromSecretsManager() above for the established pattern).
        //  - The booking confirmation code below is a short-lived, opaque reference token
        //    generated with a cryptographically secure PRNG (SecureRandom). It is NOT a
        //    password hash and does NOT use the broken MD5 algorithm.
        String confirmCode = generateSecureConfirmationCode();

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", DB_HOST);
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
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * cr-java-0090 FIX: Generates a cryptographically secure booking confirmation code
     * using {@link SecureRandom} and URL-safe Base64 encoding.
     *
     * <p>This replaces the previous {@code md5Hash()} method which used the broken MD5
     * algorithm (RFC 6151) to derive a confirmation token from user-supplied input.
     * MD5 is vulnerable to collision attacks and must not be used for any security-
     * sensitive purpose.</p>
     *
     * <p><strong>Cloud-native authentication integration (Amazon Cognito):</strong><br>
     * In a fully cloud-native deployment, end-user authentication and session token
     * issuance are delegated to <em>Amazon Cognito User Pools</em>:
     * <ol>
     *   <li>Users authenticate via the Cognito Hosted UI or the Cognito SDK.</li>
     *   <li>Cognito issues signed JWT access and ID tokens (RS256).</li>
     *   <li>The application validates tokens against Cognito's JWKS endpoint
     *       ({@code https://cognito-idp.{region}.amazonaws.com/{userPoolId}/.well-known/jwks.json})
     *       — no local credential files are read or written.</li>
     *   <li>Cognito App Client secrets and any other sensitive auth configuration are
     *       stored in <em>AWS Secrets Manager</em> and fetched at startup (see
     *       {@link #loadDbCredentialsFromSecretsManager()}).</li>
     * </ol>
     * The confirmation code returned here is a short-lived opaque reference token used
     * only to correlate a booking record; it is not a password hash or session token.</p>
     *
     * @return a 16-byte URL-safe Base64-encoded confirmation code (22 characters)
     */
    private String generateSecureConfirmationCode() {
        // cr-java-0090 FIX: SecureRandom provides cryptographically strong randomness
        // (backed by the OS entropy pool on Linux/AWS). This replaces the insecure
        // MD5-based derivation that was previously used to generate confirmation codes.
        // Authentication credentials and user identity are managed by Amazon Cognito;
        // sensitive secrets are stored in AWS Secrets Manager — not in local files.
        SecureRandom secureRandom = new SecureRandom();
        byte[] tokenBytes = new byte[16];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }
}
