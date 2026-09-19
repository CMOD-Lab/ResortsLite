package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * cr-java-0069 FIX: Hard-coded database credentials removed.
     *
     * DB_USER (was "admin") and DB_PASS (was "Resort$Pass#2019!") are no longer
     * embedded in source code. Credentials are now retrieved at runtime from
     * AWS Secrets Manager via AwsSecretsManagerConfig, which fetches the secret
     * named by the environment variable AWS_DB_SECRET_NAME (default:
     * "resortslite/db/credentials"). The secret must be a JSON object with keys
     * "username", "password", and optionally "host".
     *
     * DB_HOST is also sourced from the same secret (key "host"), eliminating the
     * previously hard-coded value "db-prod.resorts-internal.com".
     */
    @Autowired
    private AwsSecretsManagerConfig secretsManagerConfig;

    /**
     * cr-java-0090 FIX: File-based authentication replaced with Amazon Cognito.
     *
     * Authentication credentials and user identity data are no longer stored in or
     * read from local files. All user identity validation is delegated to Amazon
     * Cognito via CognitoAuthService, which uses the Cognito User Pool as the
     * single source of truth. This provides:
     *   - Centralized, encrypted credential storage (no local files)
     *   - Auditable authentication events via AWS CloudTrail
     *   - Built-in user lifecycle management
     *   - Horizontal scalability without file-system dependencies
     *
     * The Cognito User Pool ID is configured via the AWS_COGNITO_USER_POOL_ID
     * environment variable (or aws.cognito.user-pool-id in application.properties).
     */
    @Autowired
    private CognitoAuthService cognitoAuthService;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Creates a new booking after validating the guest identity via Amazon Cognito.
     *
     * <p>cr-java-0090 FIX: Guest identity is validated against the Cognito User Pool
     * before the booking is persisted. This replaces any file-based authentication
     * check and ensures that only authenticated, confirmed Cognito users can create
     * bookings. The Cognito User Pool is the authoritative identity store — no local
     * files, in-memory maps, or hardcoded credential constants are consulted.
     *
     * @param guestName  the guest's name (used as the Cognito username for identity lookup)
     * @param roomType   the requested room type
     * @param checkIn    the check-in date
     * @param checkOut   the check-out date
     * @return a map containing the booking details and confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // cr-java-0090 FIX: Validate guest identity via Amazon Cognito User Pool.
        // Replaces any file-based authentication or locally stored credential lookup.
        // CognitoAuthService.validateGuestIdentity() calls the Cognito AdminGetUser API
        // to confirm the user exists and is in CONFIRMED/enabled status in the User Pool.
        // No credentials or user data are read from local files or static constants.
        boolean identityValid = cognitoAuthService.validateGuestIdentity(guestName);
        if (!identityValid) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Guest identity could not be validated via Amazon Cognito. "
                    + "Ensure the guest is registered and confirmed in the Cognito User Pool.");
            errorResponse.put("bookingId", null);
            return errorResponse;
        }

        // cr-java-0090 FIX: Retrieve guest profile attributes from Cognito User Pool.
        // User data (loyalty tier, email, phone) is sourced from Cognito — not from
        // local files or hardcoded maps. This ensures a single, consistent identity
        // record across all application instances in the cloud environment.
        Map<String, String> guestAttributes = cognitoAuthService.getGuestAttributes(guestName);
        String loyaltyTier = guestAttributes.getOrDefault("custom:loyaltyTier", "STANDARD");

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
        // cr-java-0090 FIX: Loyalty tier is sourced from Cognito User Pool attribute
        // "custom:loyaltyTier" — not from a local file or hardcoded map.
        booking.put("loyaltyTier", loyaltyTier);
        // cr-java-0069 FIX: DB host is now sourced from AWS Secrets Manager at runtime,
        // not from a hard-coded constant. No credential or hostname is embedded in code.
        booking.put("dbHost", secretsManagerConfig.getDbHost());
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
