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

    // Updated: Hardcoded database credentials removed from source code.
    // DB host, username, and password are now injected from environment variables
    // (AWS Secrets Manager / Parameter Store in cloud deployments).
    // Fix for: cr-java-0021, sec-cred-001 [Security Health / Critical]
    @Value("${DB_HOST:localhost}")
    private String dbHost;

    // Updated: Payment API endpoint externalised to environment variable.
    // Hardcoded IP addresses and plain HTTP URLs replaced with configurable HTTPS endpoint.
    // Fix for: cr-java-0021, cr-java-0088 [Cloud Compatibility / Mandatory]
    @Value("${PAYMENT_ENDPOINT:https://payment-svc.internal:9090/payments/charge}")
    private String paymentApi;

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Updated: SQL injection vulnerability fixed by using parameterised query with
        // JdbcTemplate '?' placeholders instead of string concatenation.
        // Fix for: sql-inject-001 [Security Health / Critical]
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Updated: replaced MD5 (broken, RFC 6151) with SHA-256 for confirmation code hashing
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

    public Map<String, Object> getBookingById(String bookingId) {
        // Updated: SQL injection vulnerability fixed by using parameterised query with
        // JdbcTemplate '?' placeholder instead of string concatenation.
        // Fix for: sql-inject-001 [Security Health / Critical]
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // Updated: Refactored calculateRoomPrice to reduce cyclomatic complexity.
    // Extracted room base price and discount lookups into helper methods to
    // reduce branching below the complexity threshold.
    // Fix for: [Code Sustainability / High] high cyclomatic complexity
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = resolveBasePrice(roomType);
        basePrice = applySeasonMultiplier(basePrice, season);
        basePrice = applyLoyaltyDiscount(basePrice, loyalty);
        basePrice = applyStayLengthDiscount(basePrice, nights);
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    private double resolveBasePrice(String roomType) {
        return switch (roomType) {
            case "DELUXE" -> 200.0;
            case "SUITE"  -> 350.0;
            case "VILLA"  -> 600.0;
            default       -> 120.0; // STANDARD and unknown types
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

    private double applyStayLengthDiscount(double price, int nights) {
        if (nights >= 14) return price * 0.90;
        if (nights >= 7)  return price * 0.95;
        return price;
    }

    // Updated: Extracted room type validation to a shared constant set,
    // eliminating duplicated validation logic present in calculateRoomPrice.
    // Fix for: dup-logic-001 [Code Sustainability / Medium]
    private static final java.util.Set<String> VALID_ROOM_TYPES =
            java.util.Set.of("STANDARD", "DELUXE", "SUITE", "VILLA");

    public boolean isRoomAvailable(String roomType) {
        return VALID_ROOM_TYPES.contains(roomType);
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     * Replaces the previously used MD5 algorithm (broken per RFC 6151).
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
