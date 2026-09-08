package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native booking operations.
 *
 * <p>Hard-coded database credentials (blockers 8-9: cr-java-0069) and the
 * file-based credential reference (blocker-18: cr-java-0090) have been removed.
 * All sensitive values are now externalised to Google Secret Manager and surfaced
 * to the application via Spring Cloud GCP Secret Manager property-source integration
 * (using {@code sm://} references in {@code application.properties}) or plain
 * environment variables for non-sensitive configuration.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -----------------------------------------------------------------------
    // Blocker-8 (cr-java-0069): DB_USER hard-coded credential removed.
    // Value is now resolved at runtime from Google Secret Manager via the
    // Spring Cloud GCP property source (sm:// reference in application.properties).
    // -----------------------------------------------------------------------
    @Value("${spring.datasource.username:sa}")
    private String dbUsername;

    // -----------------------------------------------------------------------
    // Blocker-9 (cr-java-0069): DB_PASS hard-coded credential removed.
    // Value is now resolved at runtime from Google Secret Manager via the
    // Spring Cloud GCP property source (sm:// reference in application.properties).
    // -----------------------------------------------------------------------
    @Value("${spring.datasource.password:}")
    private String dbPassword;

    // -----------------------------------------------------------------------
    // Blocker-18 (cr-java-0090): File-based credential storage removed.
    // Payment API endpoint is externalised to an environment variable so it
    // can be injected by Cloud Run / GKE without touching source code.
    // -----------------------------------------------------------------------
    @Value("${app.payment.endpoint:https://payment-svc.internal/charge}")
    private String paymentApi;

    /**
     * Creates a new booking record.
     *
     * @param guestName guest full name
     * @param roomType  room category (STANDARD / DELUXE / SUITE / VILLA)
     * @param checkIn   check-in date string
     * @param checkOut  check-out date string
     * @return booking details map
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        String confirmCode = md5Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // Blocker-8/9 (cr-java-0069): DB_HOST hard-coded value removed from response.
        return booking;
    }

    /**
     * Retrieves a booking by its identifier.
     *
     * @param bookingId the booking identifier
     * @return booking details map or error entry
     */
    public Map<String, Object> getBookingById(String bookingId) {
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
     * @param roomType room category
     * @param nights   number of nights
     * @param season   season code (PEAK / OFF / standard)
     * @param loyalty  loyalty tier (GOLD / PLATINUM / DIAMOND / none)
     * @return formatted total price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = 0;
        if (roomType.equals("STANDARD")) { basePrice = 120.0; }
        else if (roomType.equals("DELUXE")) { basePrice = 200.0; }
        else if (roomType.equals("SUITE")) { basePrice = 350.0; }
        else if (roomType.equals("VILLA")) { basePrice = 600.0; }
        else { basePrice = 120.0; }
        if (season.equals("PEAK")) { basePrice = basePrice * 1.5; }
        else if (season.equals("OFF")) { basePrice = basePrice * 0.8; }
        if (loyalty.equals("GOLD")) { basePrice = basePrice * 0.9; }
        else if (loyalty.equals("PLATINUM")) { basePrice = basePrice * 0.8; }
        else if (loyalty.equals("DIAMOND")) { basePrice = basePrice * 0.7; }
        if (nights >= 14) { basePrice = basePrice * 0.90; }
        else if (nights >= 7) { basePrice = basePrice * 0.95; }
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room type is available.
     *
     * @param roomType room category to check
     * @return {@code true} if the room type is valid and available
     */
    public boolean isRoomAvailable(String roomType) {
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    /**
     * Generates a report summary for the given month.
     *
     * <p>Blocker-18 (cr-java-0090): {@code paymentApi} is now injected from an
     * environment variable instead of being read from a local credential file.
     *
     * @param month month identifier
     * @return report generation status string
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    private String md5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
