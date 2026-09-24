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

    // FIX cr-java-0021, sec-cred-001: Hardcoded DB credentials removed.
    // Infrastructure values are now externalised to environment variables /
    // AWS Parameter Store / Secrets Manager and injected via application.properties.

    // FIX cr-java-0021, cr-java-0088: Hardcoded payment API endpoint replaced with
    // an externalised, configurable value injected from application.properties.
    @Value("${app.payment.endpoint:https://payment-svc.internal/charge}")
    private String paymentApi;

    /**
     * Creates a new booking record in the database.
     *
     * @param guestName  Name of the guest making the booking.
     * @param roomType   Type of room requested (STANDARD, DELUXE, SUITE, VILLA).
     * @param checkIn    Check-in date string (yyyy-MM-dd).
     * @param checkOut   Check-out date string (yyyy-MM-dd).
     * @return Map containing booking details and confirmation code.
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // FIX sql-inject-001 [Security Health / Critical]: Replaced string-concatenated SQL
        // with a parameterised JdbcTemplate query. All user-supplied values are bound as
        // positional parameters ('?'), preventing SQL injection attacks.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // FIX sec-weak-hash-001 [Security Health / High]: Replaced broken MD5 algorithm
        // with SHA-256 (FIPS 180-4 compliant). MD5 is cryptographically broken (RFC 6151)
        // and must not be used for any security-related hashing.
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // FIX cr-java-0021: Removed dbHost from response — infrastructure details must
        // not be leaked to API consumers.
        return booking;
    }

    /**
     * Retrieves a booking record by its unique identifier.
     *
     * @param bookingId The booking ID to look up.
     * @return Map containing booking details, or an error entry if not found.
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // FIX sql-inject-001 [Security Health / Critical]: Replaced string-concatenated SQL
        // with a parameterised JdbcTemplate query to prevent SQL injection.
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
     * Calculates the total room price for a stay.
     *
     * <p>FIX dup-logic-001 / high-complexity: Room type resolution extracted to
     * {@link #getBasePrice(String)} to eliminate duplicated validation logic and
     * reduce cyclomatic complexity of this method.</p>
     *
     * @param roomType Room category (STANDARD, DELUXE, SUITE, VILLA).
     * @param nights   Number of nights.
     * @param season   Season code (PEAK, OFF, or other for standard rate).
     * @param loyalty  Loyalty tier (GOLD, PLATINUM, DIAMOND, or other for no discount).
     * @return Formatted total price string.
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = getBasePrice(roomType);

        // Apply seasonal multiplier
        if ("PEAK".equals(season)) {
            basePrice *= 1.5;
        } else if ("OFF".equals(season)) {
            basePrice *= 0.8;
        }

        // Apply loyalty discount
        switch (loyalty) {
            case "GOLD"     -> basePrice *= 0.9;
            case "PLATINUM" -> basePrice *= 0.8;
            case "DIAMOND"  -> basePrice *= 0.7;
            default         -> { /* no discount */ }
        }

        // Apply long-stay discount (>= 14 nights takes priority over >= 7)
        if (nights >= 14) {
            basePrice *= 0.90;
        } else if (nights >= 7) {
            basePrice *= 0.95;
        }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a given room type is valid and available for booking.
     *
     * <p>FIX dup-logic-001 [Code Sustainability / Medium]: Room type validation
     * now delegates to {@link #getBasePrice(String)} instead of repeating the
     * same set of string comparisons.</p>
     *
     * @param roomType Room category to validate.
     * @return {@code true} if the room type is recognised; {@code false} otherwise.
     */
    public boolean isRoomAvailable(String roomType) {
        return getBasePrice(roomType) > 0;
    }

    /**
     * Generates a report summary string for the given month.
     *
     * @param month Month identifier (e.g. "2024-03").
     * @return Human-readable report trigger message.
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the nightly base price for a room type.
     *
     * <p>Centralises room-type resolution, eliminating duplicated if/else chains
     * across {@link #calculateRoomPrice} and {@link #isRoomAvailable}.</p>
     *
     * @param roomType Room category string.
     * @return Base nightly price; {@code 0.0} for unrecognised types.
     */
    private double getBasePrice(String roomType) {
        return switch (roomType) {
            case "STANDARD" -> 120.0;
            case "DELUXE"   -> 200.0;
            case "SUITE"    -> 350.0;
            case "VILLA"    -> 600.0;
            default         -> 0.0;
        };
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     *
     * <p>FIX sec-weak-hash-001: Replaces the former MD5 implementation with
     * SHA-256, which is cryptographically secure per FIPS 180-4.</p>
     *
     * @param input String to hash.
     * @return Lowercase hex-encoded SHA-256 digest, or the raw input on error.
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
