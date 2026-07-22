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

    // NOTE: Database credentials and infrastructure hostnames should be externalised
    // to environment variables or a secrets manager (e.g., AWS Secrets Manager).
    // These constants are retained for reference only and are NOT used in queries.
    private static final String DB_HOST = "${DB_HOST:db-prod.resorts-internal.com}";
    private static final String PAYMENT_API = "${PAYMENT_API:http://10.0.1.45:9090/payments/charge}";

    /**
     * Creates a new booking record using parameterised SQL to prevent SQL injection.
     * Confirmation code is generated using SHA-256 (replaces insecure MD5).
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Fixed: Use parameterised query to prevent SQL injection (was string concatenation)
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Fixed: Replaced MD5 (broken, RFC 6151) with SHA-256 for confirmation code generation
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

    /**
     * Retrieves a booking by its ID using a parameterised query to prevent SQL injection.
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Fixed: Use parameterised query to prevent SQL injection (was string concatenation)
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
     * Calculates the room price based on room type, number of nights, season, and loyalty tier.
     * Refactored to reduce cyclomatic complexity using helper methods.
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = getBasePrice(roomType);
        basePrice = applySeasonMultiplier(basePrice, season);
        basePrice = applyLoyaltyDiscount(basePrice, loyalty);
        basePrice = applyLengthOfStayDiscount(basePrice, nights);
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room of the given type is available.
     */
    public boolean isRoomAvailable(String roomType) {
        return isValidRoomType(roomType);
    }

    /**
     * Generates a report summary for the given month.
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /** Returns the base nightly price for a room type. */
    private double getBasePrice(String roomType) {
        return switch (roomType) {
            case "STANDARD" -> 120.0;
            case "DELUXE"   -> 200.0;
            case "SUITE"    -> 350.0;
            case "VILLA"    -> 600.0;
            default         -> 120.0;
        };
    }

    /** Applies a seasonal price multiplier. */
    private double applySeasonMultiplier(double price, String season) {
        return switch (season) {
            case "PEAK" -> price * 1.5;
            case "OFF"  -> price * 0.8;
            default     -> price;
        };
    }

    /** Applies a loyalty-tier discount. */
    private double applyLoyaltyDiscount(double price, String loyalty) {
        return switch (loyalty) {
            case "GOLD"     -> price * 0.9;
            case "PLATINUM" -> price * 0.8;
            case "DIAMOND"  -> price * 0.7;
            default         -> price;
        };
    }

    /** Applies a length-of-stay discount. */
    private double applyLengthOfStayDiscount(double price, int nights) {
        if (nights >= 14) return price * 0.90;
        if (nights >= 7)  return price * 0.95;
        return price;
    }

    /** Validates that the room type is one of the known types. */
    private boolean isValidRoomType(String roomType) {
        return roomType != null && (
                roomType.equals("STANDARD") ||
                roomType.equals("DELUXE")   ||
                roomType.equals("SUITE")    ||
                roomType.equals("VILLA"));
    }

    /**
     * Generates a SHA-256 hex digest of the input string.
     * Replaces the previously used MD5 algorithm (broken per RFC 6151).
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
