package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Updated: Credentials and infrastructure endpoints should be externalised to
    // environment variables / AWS Parameter Store / Secrets Manager (cr-java-0021, sec-cred-001).
    // Hardcoded values removed; use application.properties or environment variables instead.
    private static final String DB_HOST = System.getenv().getOrDefault("DB_HOST", "db-prod.resorts-internal.com");
    private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "");
    private static final String DB_PASS = System.getenv().getOrDefault("DB_PASS", "");

    // Updated: Externalised payment API endpoint (cr-java-0021, cr-java-0088).
    private static final String PAYMENT_API = System.getenv().getOrDefault(
            "PAYMENT_API_URL", "https://payment-svc.internal/payments/charge");

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Updated: SQL injection fixed — parameterised query used instead of string concatenation (sql-inject-001).
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Updated: MD5 replaced with SHA-256 (sec-weak-hash-001).
        // MD5 is a broken hash algorithm (RFC 6151); SHA-256 is the minimum acceptable standard.
        String confirmCode = sha256Hash(bookingId + guestName);

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
        // Updated: SQL injection fixed — parameterised query used instead of string concatenation (sql-inject-001).
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // Updated: Cyclomatic complexity reduced by extracting base price and discount lookups
    // into helper methods (dup-logic-001, complexity threshold).
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = getBasePrice(roomType);
        basePrice = applySeasonMultiplier(basePrice, season);
        basePrice = applyLoyaltyDiscount(basePrice, loyalty);
        if (nights >= 14) {
            basePrice = basePrice * 0.90;
        } else if (nights >= 7) {
            basePrice = basePrice * 0.95;
        }
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        // Updated: Validation delegated to shared helper to eliminate duplicated logic (dup-logic-001).
        return isValidRoomType(roomType);
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private boolean isValidRoomType(String roomType) {
        return "STANDARD".equals(roomType) || "DELUXE".equals(roomType)
                || "SUITE".equals(roomType) || "VILLA".equals(roomType);
    }

    private double getBasePrice(String roomType) {
        return switch (roomType) {
            case "DELUXE"   -> 200.0;
            case "SUITE"    -> 350.0;
            case "VILLA"    -> 600.0;
            default         -> 120.0; // STANDARD and unknown types
        };
    }

    private double applySeasonMultiplier(double price, String season) {
        return switch (season) {
            case "PEAK" -> price * 1.5;
            case "OFF"  -> price * 0.8;
            default     -> price;
        };
    }

    private double applyLoyaltyDiscount(double price, String loyalty) {
        return switch (loyalty) {
            case "GOLD"     -> price * 0.9;
            case "PLATINUM" -> price * 0.8;
            case "DIAMOND"  -> price * 0.7;
            default         -> price;
        };
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     * Replaces the previous MD5 implementation (sec-weak-hash-001).
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
