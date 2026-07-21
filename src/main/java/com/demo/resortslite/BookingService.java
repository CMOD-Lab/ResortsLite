package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service layer for booking operations.
 *
 * <p>Migration notes (Java 1.8 → Java 21 / Spring Boot 2.7.x → 3.2.x):
 * <ul>
 *   <li>SQL injection fix: all queries use parameterised JDBC (? placeholders)</li>
 *   <li>MD5 replaced with SHA-256 for confirmation code hashing</li>
 *   <li>Hardcoded DB credentials replaced with environment-variable resolution</li>
 *   <li>Room-type validation refactored into an enum to reduce cyclomatic complexity</li>
 *   <li>Switch expressions use Java 14+ arrow syntax (compatible with Java 21)</li>
 *   <li>DB host no longer leaked in booking response payload (security hardening)</li>
 * </ul>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Infrastructure endpoints and credentials must be supplied via environment
    // variables or a secrets manager (e.g., AWS Secrets Manager / Parameter Store).
    // Fix: Hardcoded Database Credentials in Source Code — now resolved from env vars.
    private static final String DB_HOST =
            System.getenv().getOrDefault("DB_HOST", "localhost");
    private static final String DB_USER =
            System.getenv().getOrDefault("DB_USER", "sa");
    private static final String DB_PASS =
            System.getenv().getOrDefault("DB_PASS", "");

    // Payment API endpoint resolved from environment variable.
    private static final String PAYMENT_API =
            System.getenv().getOrDefault("PAYMENT_API", "https://payment-svc.internal:9090/payments/charge");

    /**
     * Room types supported by the booking system.
     * Fix: High Cyclomatic Complexity — room-type validation and pricing
     * consolidated into a single enum, eliminating duplicated if/else chains.
     */
    private enum RoomType {
        STANDARD(120.0), DELUXE(200.0), SUITE(350.0), VILLA(600.0);

        final double basePrice;

        RoomType(double basePrice) {
            this.basePrice = basePrice;
        }

        static RoomType fromString(String value) {
            try {
                return RoomType.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                return STANDARD;
            }
        }
    }

    /**
     * Creates a new booking record in the database.
     *
     * <p>Fix (SQL Injection): Uses parameterised JDBC update — no string concatenation.
     * Fix (MD5 hashing): Confirmation code now uses SHA-256 instead of MD5.
     *
     * @param guestName the name of the guest
     * @param roomType  the type of room requested
     * @param checkIn   the check-in date string
     * @param checkOut  the check-out date string
     * @return a map containing the booking details and confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Fix: SQL Injection via String Concatenation — parameterised query used.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Fix: MD5 Used for Confirmation Code Hashing — replaced with SHA-256.
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // Security hardening: DB_HOST is NOT included in the response payload
        // to avoid leaking infrastructure details to API consumers.
        return booking;
    }

    /**
     * Retrieves a booking record by its identifier.
     *
     * <p>Fix (SQL Injection): Uses parameterised JDBC query — no string concatenation.
     *
     * @param bookingId the booking identifier
     * @return a map containing the booking details, or an error entry if not found
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Fix: SQL Injection — parameterised query used.
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
     * season, and loyalty tier.
     *
     * <p>Fix: High Cyclomatic Complexity — pricing logic decomposed into
     * focused private helper methods; switch expressions use Java 21 arrow syntax.
     *
     * @param roomType the type of room
     * @param nights   the number of nights
     * @param season   the season code (PEAK / OFF / standard)
     * @param loyalty  the loyalty tier (GOLD / PLATINUM / DIAMOND / none)
     * @return the formatted total price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = RoomType.fromString(roomType).basePrice;

        basePrice = applySeasonMultiplier(basePrice, season);
        basePrice = applyLoyaltyDiscount(basePrice, loyalty);
        basePrice = applyLengthOfStayDiscount(basePrice, nights);

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room of the given type is available for booking.
     *
     * @param roomType the type of room to check
     * @return {@code true} if the room type is valid and available
     */
    public boolean isRoomAvailable(String roomType) {
        try {
            RoomType.valueOf(roomType.toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Triggers report generation for the specified month.
     *
     * @param month the month identifier
     * @return a status message string
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Applies a seasonal price multiplier.
     * Uses Java 14+ switch expression (compatible with Java 21).
     */
    private double applySeasonMultiplier(double price, String season) {
        return switch (season) {
            case "PEAK" -> price * 1.5;
            case "OFF"  -> price * 0.8;
            default     -> price;
        };
    }

    /**
     * Applies a loyalty-tier discount.
     * Uses Java 14+ switch expression (compatible with Java 21).
     */
    private double applyLoyaltyDiscount(double price, String loyalty) {
        return switch (loyalty) {
            case "GOLD"     -> price * 0.9;
            case "PLATINUM" -> price * 0.8;
            case "DIAMOND"  -> price * 0.7;
            default         -> price;
        };
    }

    /**
     * Applies a length-of-stay discount for extended bookings.
     */
    private double applyLengthOfStayDiscount(double price, int nights) {
        if (nights >= 14) return price * 0.90;
        if (nights >= 7)  return price * 0.95;
        return price;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     * Fix: MD5 Used for Confirmation Code Hashing — SHA-256 is cryptographically secure.
     *
     * @param input the string to hash
     * @return the hex-encoded SHA-256 digest, or the original input on error
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
