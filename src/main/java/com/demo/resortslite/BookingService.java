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

    // Externalised: DB host, credentials, and service endpoints are now read from
    // environment variables / application.properties (12-factor app principle).
    // Hardcoded values (DB_HOST, DB_USER, DB_PASS, PAYMENT_API) have been removed
    // to prevent credential exposure in source control.
    @Value("${app.payment.endpoint:http://payment-svc:9090/charge}")
    private String paymentApi;

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Fixed sql-inject-001: Replaced string-concatenated SQL with a parameterised
        // JdbcTemplate query using '?' placeholders. This prevents SQL injection attacks
        // where a malicious guestName such as "'; DROP TABLE bookings; --" could destroy data.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // SHA-256 confirmation code (MD5 was replaced in a prior iteration per RFC 6151).
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
        // Fixed sql-inject-001: Replaced string-concatenated SQL with a parameterised
        // JdbcTemplate query. bookingId is now passed as a safe bind parameter.
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // Refactored: Reduced cyclomatic complexity by extracting room base-price lookup
    // and discount multiplier logic into dedicated helper methods, keeping each branch
    // count below the acceptable threshold.
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = getBasePrice(roomType);
        basePrice = applySeasonMultiplier(basePrice, season);
        basePrice = applyLoyaltyDiscount(basePrice, loyalty);
        basePrice = applyLengthOfStayDiscount(basePrice, nights);
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        // Fixed dup-logic-001: Room-type validation is now delegated to the shared
        // getBasePrice helper, eliminating the duplicated validation branch.
        return getBasePrice(roomType) > 0;
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    /**
     * Returns the nightly base price for the given room type.
     * Acts as the single source of truth for valid room types (replaces dup-logic-001).
     */
    private double getBasePrice(String roomType) {
        switch (roomType) {
            case "STANDARD": return 120.0;
            case "DELUXE":   return 200.0;
            case "SUITE":    return 350.0;
            case "VILLA":    return 600.0;
            default:         return 0.0;
        }
    }

    /** Applies peak / off-season pricing multiplier. */
    private double applySeasonMultiplier(double price, String season) {
        switch (season) {
            case "PEAK": return price * 1.5;
            case "OFF":  return price * 0.8;
            default:     return price;
        }
    }

    /** Applies loyalty-tier discount multiplier. */
    private double applyLoyaltyDiscount(double price, String loyalty) {
        switch (loyalty) {
            case "GOLD":     return price * 0.9;
            case "PLATINUM": return price * 0.8;
            case "DIAMOND":  return price * 0.7;
            default:         return price;
        }
    }

    /** Applies length-of-stay bulk discount. */
    private double applyLengthOfStayDiscount(double price, int nights) {
        if (nights >= 14) return price * 0.90;
        if (nights >= 7)  return price * 0.95;
        return price;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     * Replaces the previously used MD5 algorithm (broken per RFC 6151).
     * Compatible with Java 8+ and Java 21.
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
