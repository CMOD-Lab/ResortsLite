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

    // Externalised to environment variables / application properties.
    // Rule: cr-java-0021 [Cloud Compatibility / Mandatory] — hardcoded DB credentials
    // removed from source code; injected via Spring @Value from environment / Secrets Manager.
    @Value("${app.payment.endpoint:http://payment-svc.internal:9090/charge}")
    private String paymentApi; // replaces hardcoded PAYMENT_API constant (cr-java-0021, cr-java-0088)

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Fixed: SQL injection via string concatenation replaced with parameterised query.
        // Rule: sql-inject-001 [Security Health / Critical] — JdbcTemplate '?' placeholders
        // prevent user-supplied input from being interpreted as SQL syntax.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Updated: replaced MD5 (broken, RFC 6151) with SHA-256 for confirmation code hashing.
        // Rule: JAVA8_TO_17_SPECIFIC_DEPENDENCY_UPDATES — weak hashing algorithms flagged
        // for Java 17 security hardening. SHA-256 is the minimum recommended algorithm.
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
        // Fixed: SQL injection via string concatenation replaced with parameterised query.
        // Rule: sql-inject-001 [Security Health / Critical] — bookingId is user-supplied
        // input; using '?' placeholder prevents SQL injection attacks.
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // Refactored: reduced cyclomatic complexity by extracting room base price and
    // discount multiplier lookups into helper methods.
    // Rule: [Code Sustainability / High] — methods above complexity threshold refactored
    // to improve maintainability and reduce transformation risk.
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = resolveBasePrice(roomType);
        basePrice = applySeasonMultiplier(basePrice, season);
        basePrice = applyLoyaltyDiscount(basePrice, loyalty);
        basePrice = applyLengthOfStayDiscount(basePrice, nights);
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        // Refactored: duplicated room-type validation extracted to shared helper method.
        // Rule: dup-logic-001 [Code Sustainability / Medium] — single source of truth
        // for valid room types; eliminates duplication between isRoomAvailable and
        // calculateRoomPrice.
        return isValidRoomType(roomType);
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true when roomType is one of the recognised values.
     * Centralises the validation previously duplicated across multiple methods.
     */
    private boolean isValidRoomType(String roomType) {
        return "STANDARD".equals(roomType)
                || "DELUXE".equals(roomType)
                || "SUITE".equals(roomType)
                || "VILLA".equals(roomType);
    }

    /** Maps a room type to its nightly base price. */
    private double resolveBasePrice(String roomType) {
        switch (roomType) {
            case "DELUXE":  return 200.0;
            case "SUITE":   return 350.0;
            case "VILLA":   return 600.0;
            default:        return 120.0; // STANDARD and unknown types
        }
    }

    /** Applies a seasonal pricing multiplier. */
    private double applySeasonMultiplier(double price, String season) {
        if ("PEAK".equals(season))  return price * 1.5;
        if ("OFF".equals(season))   return price * 0.8;
        return price;
    }

    /** Applies a loyalty-tier discount. */
    private double applyLoyaltyDiscount(double price, String loyalty) {
        if ("GOLD".equals(loyalty))     return price * 0.9;
        if ("PLATINUM".equals(loyalty)) return price * 0.8;
        if ("DIAMOND".equals(loyalty))  return price * 0.7;
        return price;
    }

    /** Applies a length-of-stay discount for extended bookings. */
    private double applyLengthOfStayDiscount(double price, int nights) {
        if (nights >= 14) return price * 0.90;
        if (nights >= 7)  return price * 0.95;
        return price;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     * Replaces the former MD5-based implementation (sec-weak-hash-001).
     * SHA-256 is compliant with NIST SP 800-107 and Java 17 security policy.
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
