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
 * Updated for Java 21 / Spring Boot 3 compatibility:
 * - Replaced MD5 (weak) with SHA-256 for confirmation code hashing.
 * - Replaced SQL string concatenation with parameterised queries to prevent SQL injection.
 * - Externalised infrastructure hostnames to environment variables.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Externalised to environment variables — do NOT hardcode credentials in source code.
    // Use AWS Secrets Manager / Parameter Store in production.
    private static final String DB_HOST =
            System.getenv().getOrDefault("DB_HOST", "localhost");
    private static final String DB_USER =
            System.getenv().getOrDefault("DB_USER", "sa");
    // DB_PASS intentionally not stored in code — read from environment at runtime.

    // Externalised payment API endpoint — must be HTTPS in cloud environments.
    private static final String PAYMENT_API =
            System.getenv().getOrDefault("PAYMENT_API_URL", "https://payment-svc.internal:9090/payments/charge");

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterised query — prevents SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Replaced MD5 with SHA-256 (RFC 6151 deprecates MD5 for security use).
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
     * season, and loyalty tier.
     *
     * @param roomType the type of room (STANDARD, DELUXE, SUITE, VILLA)
     * @param nights   number of nights
     * @param season   pricing season (PEAK, OFF, or standard)
     * @param loyalty  guest loyalty tier (GOLD, PLATINUM, DIAMOND, or none)
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

        basePrice = switch (season) {
            case "PEAK" -> basePrice * 1.5;
            case "OFF"  -> basePrice * 0.8;
            default     -> basePrice;
        };

        basePrice = switch (loyalty) {
            case "GOLD"     -> basePrice * 0.9;
            case "PLATINUM" -> basePrice * 0.8;
            case "DIAMOND"  -> basePrice * 0.7;
            default         -> basePrice;
        };

        // Long-stay discount (>= 14 nights takes priority over >= 7 nights)
        if (nights >= 14) {
            basePrice = basePrice * 0.90;
        } else if (nights >= 7) {
            basePrice = basePrice * 0.95;
        }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room of the given type is available for booking.
     *
     * @param roomType the room type to check
     * @return true if the room type is valid and available
     */
    public boolean isRoomAvailable(String roomType) {
        return roomType != null && switch (roomType) {
            case "STANDARD", "DELUXE", "SUITE", "VILLA" -> true;
            default -> false;
        };
    }

    /**
     * Triggers report generation for the specified month.
     *
     * @param month the month identifier (e.g., "2024-03")
     * @return status message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     * SHA-256 replaces the previously used MD5 algorithm (deprecated per RFC 6151).
     *
     * @param input the string to hash
     * @return hex-encoded SHA-256 digest, or the original input on error
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
