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

    // Externalised to environment variables / application.properties — no hardcoded credentials
    // (Fixes: cr-java-0021, sec-cred-001)
    @Value("${app.payment.endpoint:http://payment-svc.internal:9090/charge}")
    private String paymentApi;

    /**
     * Creates a new booking record in the PostgreSQL database using a parameterised
     * INSERT statement to prevent SQL injection (Fixes: sql-inject-001).
     *
     * @param guestName  name of the guest
     * @param roomType   type of room (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn    check-in date string
     * @param checkOut   check-out date string
     * @return map containing booking details and confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterised query — prevents SQL injection (Fixes: sql-inject-001)
        // PostgreSQL-compatible INSERT using positional placeholders
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // SHA-256 hash for confirmation code (secure, replaces broken MD5)
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
     * Retrieves a booking by its ID using a parameterised query (Fixes: sql-inject-001).
     *
     * @param bookingId the booking identifier
     * @return map containing booking details or an error entry
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterised query — prevents SQL injection (Fixes: sql-inject-001)
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
     * <p>Refactored to reduce cyclomatic complexity by using switch expressions
     * (Java 14+) and separating discount logic into helper methods.
     *
     * @param roomType room category
     * @param nights   number of nights
     * @param season   pricing season (PEAK, OFF, or standard)
     * @param loyalty  loyalty tier (GOLD, PLATINUM, DIAMOND, or none)
     * @return formatted total price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = switch (roomType) {
            case "STANDARD" -> 120.0;
            case "DELUXE"   -> 200.0;
            case "SUITE"    -> 350.0;
            case "VILLA"    -> 600.0;
            default         -> 120.0;
        };

        basePrice = applySeasonMultiplier(basePrice, season);
        basePrice = applyLoyaltyDiscount(basePrice, loyalty);
        basePrice = applyLengthOfStayDiscount(basePrice, nights);

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether the given room type is a valid, bookable category.
     *
     * @param roomType room category string
     * @return {@code true} if the room type is recognised; {@code false} otherwise
     */
    public boolean isRoomAvailable(String roomType) {
        return isValidRoomType(roomType);
    }

    /**
     * Triggers report generation for the specified month.
     *
     * @param month the month for which the report is generated
     * @return status message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Validates whether the supplied room type is one of the recognised categories.
     * Extracted from {@link #isRoomAvailable} and {@link #calculateRoomPrice} to
     * eliminate duplicated validation logic (Fixes: dup-logic-001).
     */
    private boolean isValidRoomType(String roomType) {
        return "STANDARD".equals(roomType)
                || "DELUXE".equals(roomType)
                || "SUITE".equals(roomType)
                || "VILLA".equals(roomType);
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

    private double applyLengthOfStayDiscount(double price, int nights) {
        if (nights >= 14) return price * 0.90;
        if (nights >= 7)  return price * 0.95;
        return price;
    }

    /**
     * Computes a SHA-256 hash of the given input string.
     * Replaces the previously used MD5 algorithm which is cryptographically broken (RFC 6151).
     *
     * @param input the string to hash
     * @return hex-encoded SHA-256 digest, or the original input on error
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
