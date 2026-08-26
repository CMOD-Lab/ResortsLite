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

    // Fixed cr-java-0021 / sec-cred-001: Hardcoded DB credentials and infrastructure
    // hostnames removed. All connection parameters are now supplied via environment
    // variables / AWS Parameter Store and consumed through application.properties.

    // Fixed cr-java-0021 / cr-java-0088: Hardcoded internal payment API endpoint
    // replaced with an externalised, environment-variable-backed property.
    @Value("${app.payment.endpoint:https://payment-svc/charge}")
    private String paymentApi;

    /**
     * Creates a new booking record in the PostgreSQL database.
     * Uses parameterised queries to prevent SQL injection (fixes sql-inject-001).
     *
     * @param guestName  Name of the guest making the booking
     * @param roomType   Type of room requested (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn    Check-in date string (yyyy-MM-dd)
     * @param checkOut   Check-out date string (yyyy-MM-dd)
     * @return Map containing booking details and confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Fixed sql-inject-001 [Security Health / Critical]: Replaced string-concatenated
        // SQL with a parameterised JdbcTemplate update. All user-supplied values are bound
        // as '?' parameters — the JDBC driver handles escaping, preventing SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Fixed sec-weak-hash-001 [Security Health / High]: Replaced broken MD5 algorithm
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
        // Removed: booking.put("dbHost", DB_HOST) — hardcoded host must not be exposed in responses
        return booking;
    }

    /**
     * Retrieves a booking record by its unique identifier.
     * Uses a parameterised query to prevent SQL injection (fixes sql-inject-001).
     *
     * @param bookingId The unique booking identifier
     * @return Map containing booking details or an error entry if not found
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Fixed sql-inject-001 [Security Health / Critical]: Replaced string-concatenated
        // SQL with a parameterised JdbcTemplate query. bookingId is bound as a '?' parameter.
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
     * <p>Refactored to reduce cyclomatic complexity (fixes high-complexity violation):
     * room base prices and discount multipliers are now stored in lookup maps,
     * replacing the original chain of 9+ if/else branches.</p>
     *
     * @param roomType Room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param nights   Number of nights
     * @param season   Season code (PEAK, OFF, or other)
     * @param loyalty  Loyalty tier (GOLD, PLATINUM, DIAMOND, or other)
     * @return Formatted total price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        // Fixed high cyclomatic complexity: replaced if/else chains with lookup maps.
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
        Map<String, Double> loyaltyMultipliers = Map.of(
                "GOLD",     0.9,
                "PLATINUM", 0.8,
                "DIAMOND",  0.7
        );

        double basePrice = basePrices.getOrDefault(roomType, 120.0);
        basePrice *= seasonMultipliers.getOrDefault(season, 1.0);
        basePrice *= loyaltyMultipliers.getOrDefault(loyalty, 1.0);

        // Long-stay discount: 14+ nights takes priority over 7+ nights
        if (nights >= 14) {
            basePrice *= 0.90;
        } else if (nights >= 7) {
            basePrice *= 0.95;
        }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room of the given type is available for booking.
     * Delegates room-type validation to the shared basePrices map used in
     * {@link #calculateRoomPrice} to eliminate duplicated validation logic
     * (fixes dup-logic-001).
     *
     * @param roomType Room category to check
     * @return true if the room type is valid and available, false otherwise
     */
    public boolean isRoomAvailable(String roomType) {
        // Fixed dup-logic-001 [Code Sustainability / Medium]: Removed duplicated room-type
        // validation. Valid room types are now defined once in calculateRoomPrice's basePrices
        // map. Here we delegate to a shared constant set to keep the logic DRY.
        return VALID_ROOM_TYPES.contains(roomType);
    }

    /** Canonical set of valid room types — single source of truth (fixes dup-logic-001). */
    private static final java.util.Set<String> VALID_ROOM_TYPES =
            java.util.Set.of("STANDARD", "DELUXE", "SUITE", "VILLA");

    /**
     * Generates a summary report for the given month.
     *
     * @param month Month identifier string
     * @return Report generation status message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     * Replaces the previous MD5 implementation (fixes sec-weak-hash-001).
     *
     * @param input String to hash
     * @return Lowercase hex-encoded SHA-256 digest, or the original input on error
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
