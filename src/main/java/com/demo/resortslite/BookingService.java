package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
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

    // Updated: Credentials and infrastructure endpoints externalised to environment
    // variables / AWS Parameter Store / Secrets Manager (fixes cr-java-0021, sec-cred-001).
    // DB_HOST is no longer needed here — connection is managed by Spring DataSource config.
    @Value("${PAYMENT_API_URL:https://payment-svc.internal/payments/charge}")
    private String paymentApi;

    /**
     * Creates a new booking record in the PostgreSQL database.
     * Uses parameterised queries to prevent SQL injection (fixes sql-inject-001).
     *
     * @param guestName  Name of the guest
     * @param roomType   Type of room (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn    Check-in date string (yyyy-MM-dd)
     * @param checkOut   Check-out date string (yyyy-MM-dd)
     * @return Map containing booking details and confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Updated: Parameterised query used to prevent SQL injection (fixes sql-inject-001).
        // PostgreSQL-compatible INSERT statement — uses standard ANSI SQL syntax.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Updated: MD5 replaced with SHA-256 (fixes sec-weak-hash-001).
        // MD5 is a broken hash algorithm (RFC 6151); SHA-256 is the minimum acceptable standard.
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // Updated: Removed dbHost from response — infrastructure details must not be
        // exposed in API responses (fixes cr-java-0021 / information disclosure).
        return booking;
    }

    /**
     * Retrieves a booking record by its ID from the PostgreSQL database.
     * Uses parameterised query to prevent SQL injection (fixes sql-inject-001).
     *
     * @param bookingId  The booking identifier
     * @return Map containing booking details or an error entry if not found
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Updated: Parameterised query used to prevent SQL injection (fixes sql-inject-001).
        // PostgreSQL-compatible SELECT statement — standard ANSI SQL syntax.
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
     * season, and loyalty tier. Applies season multipliers and loyalty discounts.
     * Cyclomatic complexity reduced by extracting helper methods (fixes dup-logic-001).
     *
     * @param roomType  Room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param nights    Number of nights
     * @param season    Season code (PEAK, OFF, or default)
     * @param loyalty   Loyalty tier (GOLD, PLATINUM, DIAMOND, or default)
     * @return Formatted total price string
     */
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

    /**
     * Checks whether the given room type is a valid/available option.
     *
     * @param roomType  Room category to validate
     * @return true if the room type is valid, false otherwise
     */
    public boolean isRoomAvailable(String roomType) {
        // Updated: Validation delegated to shared helper to eliminate duplicated logic (fixes dup-logic-001).
        return isValidRoomType(roomType);
    }

    /**
     * Triggers report generation for the specified month.
     *
     * @param month  Month identifier for the report
     * @return Status message indicating report generation was triggered
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
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
     * Replaces the previous MD5 implementation (fixes sec-weak-hash-001).
     *
     * @param input  String to hash
     * @return Hex-encoded SHA-256 digest, or the original input on error
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
