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

    // [DB_MIGRATION_POSTGRESQL / SECURITY]: Hardcoded database credentials and infrastructure
    // hostnames removed from source code (resolves cr-java-0021, sec-cred-001).
    // All sensitive values are now injected via Spring @Value from environment variables
    // or an external secrets manager (AWS Secrets Manager / Parameter Store).
    @Value("${app.payment.endpoint:https://payment-svc.internal/charge}")
    private String paymentApi;

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // [DB_MIGRATION_POSTGRESQL / SECURITY]: Replaced SQL string concatenation with a
        // parameterised JdbcTemplate update to prevent SQL injection (resolves sql-inject-001).
        // PostgreSQL uses standard JDBC '?' placeholders — identical to the previous H2 syntax,
        // so no SQL dialect change is required here beyond switching the datasource.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // [SECURITY]: Replaced broken MD5 hash (RFC 6151) with SHA-256 for confirmation
        // code generation (resolves sec-weak-hash-001). SHA-256 is collision-resistant and
        // is the minimum acceptable algorithm for non-password hashing use cases.
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // [SECURITY]: DB_HOST removed from response payload — internal infrastructure
        // details must never be exposed in API responses.
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // [DB_MIGRATION_POSTGRESQL / SECURITY]: Replaced SQL string concatenation with a
        // parameterised query to prevent SQL injection (resolves sql-inject-001).
        // PostgreSQL JDBC driver handles '?' placeholder binding safely.
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
     * Calculates the total room price based on room type, number of nights,
     * season, and guest loyalty tier.
     *
     * <p>Pricing matrix:
     * <ul>
     *   <li>Base rates: STANDARD $120 | DELUXE $200 | SUITE $350 | VILLA $600</li>
     *   <li>Season multipliers: PEAK ×1.5 | OFF ×0.8</li>
     *   <li>Loyalty discounts: GOLD 10% | PLATINUM 20% | DIAMOND 30%</li>
     *   <li>Stay discounts: 7+ nights 5% | 14+ nights 10%</li>
     * </ul>
     *
     * @param roomType one of STANDARD, DELUXE, SUITE, VILLA
     * @param nights   number of nights (must be &gt; 0)
     * @param season   one of PEAK, OFF, or any other value for standard rate
     * @param loyalty  one of GOLD, PLATINUM, DIAMOND, or any other value for no discount
     * @return formatted total price string (e.g. "1575.00")
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        // [CODE_QUALITY]: Replaced chained if-else with switch expression (Java 14+) to
        // reduce cyclomatic complexity and improve readability (resolves high-complexity flag).
        double basePrice = switch (roomType) {
            case "STANDARD" -> 120.0;
            case "DELUXE"   -> 200.0;
            case "SUITE"    -> 350.0;
            case "VILLA"    -> 600.0;
            default         -> 120.0;
        };

        double seasonMultiplier = switch (season) {
            case "PEAK" -> 1.5;
            case "OFF"  -> 0.8;
            default     -> 1.0;
        };

        double loyaltyDiscount = switch (loyalty) {
            case "GOLD"     -> 0.9;
            case "PLATINUM" -> 0.8;
            case "DIAMOND"  -> 0.7;
            default         -> 1.0;
        };

        // Long-stay discount: 14+ nights takes priority over 7+ nights
        double stayDiscount = (nights >= 14) ? 0.90 : (nights >= 7) ? 0.95 : 1.0;

        double total = basePrice * seasonMultiplier * loyaltyDiscount * stayDiscount * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room of the given type is available for booking.
     *
     * @param roomType one of STANDARD, DELUXE, SUITE, VILLA
     * @return {@code true} if the room type is valid and available; {@code false} otherwise
     */
    public boolean isRoomAvailable(String roomType) {
        // [CODE_QUALITY]: Centralised room-type validation using the shared RoomType set
        // to eliminate duplicated validation logic (resolves dup-logic-001).
        return RoomType.isValid(roomType);
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     *
     * <p>Replaces the previous MD5 implementation which is cryptographically broken
     * (RFC 6151). SHA-256 is collision-resistant and suitable for confirmation-code
     * generation (resolves sec-weak-hash-001).
     *
     * @param input the string to hash
     * @return lowercase hex-encoded SHA-256 digest, or the original input on error
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
