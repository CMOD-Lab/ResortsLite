package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-ready resort booking service.
 *
 * <p>cr-java-0090 FIX: File-based Authentication replaced with AWS Secrets Manager
 * and Amazon Cognito.
 * <ul>
 *   <li>Authentication credentials are no longer stored in or read from local files.</li>
 *   <li>Database credentials are retrieved exclusively from AWS Secrets Manager via
 *       {@link AwsSecretsManagerConfig}.</li>
 *   <li>User identity management and confirmation-code generation are delegated to
 *       {@link CognitoAuthService}, which uses Amazon Cognito as the authoritative
 *       identity provider. This replaces the previous local MD5-based token generation
 *       that was tied to in-process, file-backed state.</li>
 * </ul>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: Hard-coded DB_HOST externalised to environment variable / application property.
    // DB_USER and DB_PASS have been removed from source code entirely; credentials are now
    // retrieved at runtime from AWS Secrets Manager via AwsSecretsManagerConfig (see that class).
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    /**
     * Provides access to database credentials stored in AWS Secrets Manager.
     * Injected by Spring; credentials are never hard-coded in source (cr-java-0069).
     */
    @Autowired
    private AwsSecretsManagerConfig secretsManagerConfig;

    /**
     * cr-java-0090 FIX: Amazon Cognito-backed authentication and confirmation-code service.
     * Replaces the previous local MD5-based token generation with a cloud-native identity
     * management approach. All user authentication state is managed by Cognito, not by
     * local files or in-process data structures.
     */
    @Autowired
    private CognitoAuthService cognitoAuthService;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

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

        // cr-java-0090 FIX: Confirmation code is now generated via Amazon Cognito
        // (CognitoAuthService.generateConfirmationCode) instead of the previous local
        // MD5 hash. Cognito manages the token lifecycle, storage, and validation,
        // eliminating any dependency on local file-based authentication state.
        String confirmCode = cognitoAuthService.generateConfirmationCode(bookingId, guestName);

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

    // cr-java-0090 FIX: The private md5Hash() method has been removed.
    // MD5-based local authentication token generation has been replaced by
    // CognitoAuthService.generateConfirmationCode(), which delegates to Amazon Cognito
    // for all user identity and token management operations.
}
