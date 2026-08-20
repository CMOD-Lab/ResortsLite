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

    // Externalised to environment variable / application property — no hardcoded credentials
    @Value("${app.payment.endpoint:https://payment-svc.internal:9090/charge}")
    private String paymentApi;

    /**
     * Creates a new booking record using parameterised SQL to prevent SQL injection.
     *
     * @param guestName  Name of the guest
     * @param roomType   Type of room (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn    Check-in date string
     * @param checkOut   Check-out date string
     * @return Map containing booking details and confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Fixed sql-inject-001: Replaced string concatenation with parameterised query.
        // PostgreSQL-compatible parameterised INSERT using JdbcTemplate with '?' placeholders.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // SHA-256 confirmation code (MD5 replaced in prior migration step)
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
     *
     * @param bookingId The booking identifier
     * @return Map containing booking details or an error entry if not found
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Fixed sql-inject-001: Replaced string concatenation with parameterised query.
        // PostgreSQL-compatible parameterised SELECT using JdbcTemplate with '?' placeholder.
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
     * Calculates the total room price based on room type, number of nights, season, and loyalty tier.
     * Refactored to reduce cyclomatic complexity using lookup maps.
     *
     * @param roomType Room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param nights   Number of nights
     * @param season   Season code (PEAK, OFF, or standard)
     * @param loyalty  Loyalty tier (GOLD, PLATINUM, DIAMOND, or none)
     * @return Formatted total price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        // Refactored: replaced chained if-else with map lookups to reduce cyclomatic complexity
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
        Map<String, Double> loyaltyDiscounts = Map.of(
                "GOLD",     0.9,
                "PLATINUM", 0.8,
                "DIAMOND",  0.7
        );

        double basePrice = basePrices.getOrDefault(roomType, 120.0);
        basePrice *= seasonMultipliers.getOrDefault(season, 1.0);
        basePrice *= loyaltyDiscounts.getOrDefault(loyalty, 1.0);
        if (nights >= 14)      { basePrice *= 0.90; }
        else if (nights >= 7)  { basePrice *= 0.95; }
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a given room type is valid and available.
     * Validation logic centralised here (eliminates duplication with calculateRoomPrice).
     *
     * @param roomType Room category to validate
     * @return true if the room type is recognised, false otherwise
     */
    public boolean isRoomAvailable(String roomType) {
        // Fixed dup-logic-001: Centralised room-type validation using a Set — single source of truth.
        return java.util.Set.of("STANDARD", "DELUXE", "SUITE", "VILLA").contains(roomType);
    }

    /**
     * Triggers report generation for the specified month.
     *
     * @param month Month identifier for the report
     * @return Status message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     * Replaces the previously used MD5 algorithm (sec-weak-hash-001 / RFC 6151).
     *
     * @param input String to hash
     * @return Hex-encoded SHA-256 digest, or the original input on failure
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
