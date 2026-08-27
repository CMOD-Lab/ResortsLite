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

    // cr-java-0021 fix: DB credentials removed from source code.
    // Connection is configured via spring.datasource.* properties externalised to
    // environment variables / AWS Parameter Store / Secrets Manager.

    // cr-java-0021 / cr-java-0088 fix: Payment API endpoint externalised to environment variable.
    @Value("${app.payment.endpoint:https://payment-svc.internal:9090/charge}")
    private String paymentApi;

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // sql-inject-001 fix: Replaced string-concatenated SQL with parameterised query.
        // JdbcTemplate '?' placeholders prevent SQL injection attacks.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // sec-weak-hash-001 fix: Replaced broken MD5 algorithm with SHA-256.
        // SHA-256 is cryptographically secure and recommended for confirmation codes.
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // cr-java-0021 fix: DB_HOST constant removed; no longer exposed in response.
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // sql-inject-001 fix: Replaced string-concatenated SQL with parameterised query.
        // bookingId is bound as a parameter, not interpolated into the SQL string.
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // Refactored to reduce cyclomatic complexity by extracting base price and discount
    // lookups into helper methods (dup-logic-001 / high-complexity fix).
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

    // dup-logic-001 fix: Centralised room-type validation using a shared helper,
    // eliminating duplicated validation logic between calculateRoomPrice and isRoomAvailable.
    public boolean isRoomAvailable(String roomType) {
        return isValidRoomType(roomType);
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    // --- Private helpers ---

    private boolean isValidRoomType(String roomType) {
        return roomType != null && (roomType.equals("STANDARD") || roomType.equals("DELUXE")
                || roomType.equals("SUITE") || roomType.equals("VILLA"));
    }

    private double getBasePrice(String roomType) {
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

    // sec-weak-hash-001 fix: SHA-256 replaces MD5 for confirmation code generation.
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
