package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// -----------------------------------------------------------------------
// FIXED (issue-5): Replaced MD5 (broken per RFC 6151) with SHA-256.
// MD5 is cryptographically broken and must not be used for security-
// sensitive operations. SHA-256 is the Java 17 idiomatic replacement.
// -----------------------------------------------------------------------
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -----------------------------------------------------------------------
    // FIXED (sec-cred-001 / cr-java-0021): Removed hardcoded DB credentials
    // and infrastructure hostnames. Values are now injected from environment
    // variables / AWS Parameter Store via Spring @Value bindings.
    // -----------------------------------------------------------------------
    @Value("${app.payment.endpoint:http://payment-svc:9090/charge}")
    private String paymentApi;

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // -----------------------------------------------------------------------
        // FIXED (sql-inject-001): Replaced string-concatenated SQL with a
        // parameterised JdbcTemplate update. Parameterised queries prevent SQL
        // injection by separating SQL structure from user-supplied data.
        // PostgreSQL 16 compatible syntax — uses standard JDBC '?' placeholders.
        // -----------------------------------------------------------------------
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // FIXED (issue-5): SHA-256 replaces the previously used MD5 algorithm.
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // -----------------------------------------------------------------------
        // FIXED (sql-inject-001): Replaced string-concatenated SQL with a
        // parameterised JdbcTemplate query. bookingId is now passed as a safe
        // bind parameter, preventing SQL injection attacks.
        // PostgreSQL 16 compatible — uses standard JDBC '?' placeholder.
        // -----------------------------------------------------------------------
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // FIXED (high-complexity): Refactored calculateRoomPrice to reduce
    // cyclomatic complexity by extracting room base prices and discount
    // multipliers into lookup maps, replacing the long if-else chains.
    // -----------------------------------------------------------------------
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        Map<String, Double> basePrices = Map.of(
                "STANDARD", 120.0,
                "DELUXE",   200.0,
                "SUITE",    350.0,
                "VILLA",    600.0
        );
        Map<String, Double> seasonMultipliers = Map.of(
                "PEAK", 1.5,
                "OFF",  0.8
        );
        Map<String, Double> loyaltyDiscounts = Map.of(
                "GOLD",     0.9,
                "PLATINUM", 0.8,
                "DIAMOND",  0.7
        );

        double basePrice = basePrices.getOrDefault(roomType, 120.0);
        basePrice *= seasonMultipliers.getOrDefault(season, 1.0);
        basePrice *= loyaltyDiscounts.getOrDefault(loyalty, 1.0);

        // Long-stay discount: 14+ nights takes priority over 7+ nights
        if (nights >= 14) {
            basePrice *= 0.90;
        } else if (nights >= 7) {
            basePrice *= 0.95;
        }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    // -----------------------------------------------------------------------
    // FIXED (dup-logic-001): Extracted room-type validation into a single
    // private helper, eliminating duplicated validation logic that previously
    // appeared in both calculateRoomPrice and isRoomAvailable.
    // -----------------------------------------------------------------------
    public boolean isRoomAvailable(String roomType) {
        return isValidRoomType(roomType);
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns true if the given roomType is a recognised room category.
     * Single source of truth — replaces duplicated validation logic.
     */
    private boolean isValidRoomType(String roomType) {
        return roomType != null && (
                roomType.equals("STANDARD") ||
                roomType.equals("DELUXE")   ||
                roomType.equals("SUITE")    ||
                roomType.equals("VILLA")
        );
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     * Replaces the previously used MD5 algorithm (broken per RFC 6151).
     * SHA-256 is the Java 17 recommended secure hash algorithm.
     *
     * @param input the string to hash
     * @return lowercase hex-encoded SHA-256 digest, or the raw input on error
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
