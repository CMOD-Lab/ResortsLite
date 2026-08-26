package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service layer for resort booking operations.
 *
 * <p>All database credentials and external service endpoints are resolved from
 * environment variables so that no sensitive data is embedded in source code.
 * In production, supply these via AWS Secrets Manager, Parameter Store, or
 * Kubernetes Secrets.</p>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // Externalised configuration — resolved from environment variables at startup.
    // Hardcoded credentials and URLs have been removed from source code.
    // -------------------------------------------------------------------------

    /** Database host — injected via DB_HOST environment variable. */
    private static final String DB_HOST = System.getenv().getOrDefault("DB_HOST", "localhost");

    /** Database username — injected via DB_USER environment variable. */
    private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "");

    /** Database password — injected via DB_PASS environment variable. */
    private static final String DB_PASS = System.getenv().getOrDefault("DB_PASS", "");

    /**
     * Payment API endpoint — injected via PAYMENT_API_URL environment variable.
     * Updated from plain HTTP to HTTPS to enforce transport-layer encryption.
     */
    private static final String PAYMENT_API = System.getenv().getOrDefault(
            "PAYMENT_API_URL", "https://payment-service.internal:9090/payments/charge");

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Creates a new booking record for the given guest and room details.
     *
     * <p>Uses a parameterised JDBC query to prevent SQL injection.
     * The confirmation code is generated with SHA-256 (replaces broken MD5).</p>
     *
     * @param guestName  name of the guest
     * @param roomType   type of room (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn    check-in date string (ISO-8601 recommended)
     * @param checkOut   check-out date string (ISO-8601 recommended)
     * @return a map containing booking details and confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterised query — prevents SQL injection via string concatenation.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // SHA-256 replaces broken MD5 hash for confirmation code generation.
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

    /**
     * Retrieves a booking record by its unique identifier.
     *
     * <p>Uses a parameterised JDBC query to prevent SQL injection.</p>
     *
     * @param bookingId the booking identifier
     * @return a map containing booking details, or an error entry if not found
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterised query — prevents SQL injection.
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
     * <p>Refactored to reduce cyclomatic complexity by extracting base-price
     * lookup and discount logic into dedicated private helper methods.
     * The original monolithic method had a cyclomatic complexity of ~12;
     * each helper now has a complexity of ≤4.</p>
     *
     * @param roomType room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param nights   number of nights
     * @param season   pricing season (PEAK, OFF, or standard)
     * @param loyalty  loyalty tier (GOLD, PLATINUM, DIAMOND, or none)
     * @return formatted total price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = resolveBasePrice(roomType);
        basePrice = applySeasonMultiplier(basePrice, season);
        basePrice = applyLoyaltyDiscount(basePrice, loyalty);
        basePrice = applyLengthOfStayDiscount(basePrice, nights);
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room of the given type is available for booking.
     *
     * @param roomType room category to check
     * @return {@code true} if the room type is valid and available
     */
    public boolean isRoomAvailable(String roomType) {
        return isValidRoomType(roomType);
    }

    /**
     * Triggers report generation for the specified month.
     *
     * @param month the month for which the report is generated
     * @return a status message string
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the base nightly price for the given room type.
     * Uses Java 17 switch expressions for concise, exhaustive matching.
     */
    private double resolveBasePrice(String roomType) {
        return switch (roomType) {
            case "DELUXE" -> 200.0;
            case "SUITE"  -> 350.0;
            case "VILLA"  -> 600.0;
            default       -> 120.0; // STANDARD and unrecognised types
        };
    }

    /** Applies a seasonal pricing multiplier to the base price. */
    private double applySeasonMultiplier(double price, String season) {
        return switch (season) {
            case "PEAK" -> price * 1.5;
            case "OFF"  -> price * 0.8;
            default     -> price;
        };
    }

    /** Applies a loyalty-tier discount to the price. */
    private double applyLoyaltyDiscount(double price, String loyalty) {
        return switch (loyalty) {
            case "GOLD"     -> price * 0.9;
            case "PLATINUM" -> price * 0.8;
            case "DIAMOND"  -> price * 0.7;
            default         -> price;
        };
    }

    /** Applies a length-of-stay discount for extended bookings (7+ or 14+ nights). */
    private double applyLengthOfStayDiscount(double price, int nights) {
        if (nights >= 14) return price * 0.90;
        if (nights >= 7)  return price * 0.95;
        return price;
    }

    /** Validates that the room type is one of the recognised categories. */
    private boolean isValidRoomType(String roomType) {
        return roomType.equals("STANDARD") || roomType.equals("DELUXE")
                || roomType.equals("SUITE") || roomType.equals("VILLA");
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     *
     * <p>Replaces the previously used MD5 algorithm, which is cryptographically
     * broken and unsuitable for security-sensitive operations (CWE-327).</p>
     *
     * @param input the string to hash
     * @return hex-encoded SHA-256 digest, or the original input on error
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
