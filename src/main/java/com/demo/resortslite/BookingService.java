package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService handles resort booking operations.
 * Credentials are loaded from Azure Key Vault using DefaultAzureCredential (cr-java-0069).
 * File-based authentication replaced with Azure Active Directory / Spring Security (cr-java-0090).
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069: Azure Key Vault URI injected via environment variable — no hard-coded credentials.
    @Value("${azure.keyvault.uri}")
    private String keyVaultUri;

    // cr-java-0069: DB_HOST externalized to environment variable / Azure App Configuration.
    @Value("${app.db.host:${DB_HOST:}}")
    private String dbHost;

    // cr-java-0071: Payment API URL externalized to Azure App Configuration / environment variable.
    @Value("${app.payment.endpoint}")
    private String paymentApi;

    /**
     * Lazily resolves the database username from Azure Key Vault.
     * Replaces hard-coded DB_USER constant (cr-java-0069).
     *
     * @return database username secret value
     */
    private String getDbUser() {
        SecretClient secretClient = new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        return secretClient.getSecret("db-username").getValue();
    }

    /**
     * Lazily resolves the database password from Azure Key Vault.
     * Replaces hard-coded DB_PASS constant (cr-java-0069).
     *
     * @return database password secret value
     */
    private String getDbPassword() {
        SecretClient secretClient = new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        return secretClient.getSecret("db-password").getValue();
    }

    /**
     * Creates a new booking record using parameterized SQL to prevent injection.
     *
     * @param guestName guest full name
     * @param roomType  room category
     * @param checkIn   check-in date string
     * @param checkOut  check-out date string
     * @return booking details map
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterized query — prevents SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // SHA-256 confirmation code — replaces broken MD5 hash.
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // cr-java-0069: dbHost is now externalized — not hard-coded in source.
        booking.put("dbHost", dbHost);
        return booking;
    }

    /**
     * Retrieves a booking by its ID using a parameterized query.
     *
     * @param bookingId the booking identifier
     * @return booking details map
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterized query — prevents SQL injection.
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
     * Calculates the total room price based on type, nights, season, and loyalty tier.
     *
     * @param roomType room category
     * @param nights   number of nights
     * @param season   season code (PEAK / OFF / standard)
     * @param loyalty  loyalty tier (GOLD / PLATINUM / DIAMOND / standard)
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
     * Checks whether a given room type is valid and available.
     *
     * @param roomType room category to check
     * @return true if the room type is valid
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
     * @param month the month identifier
     * @return report generation status string
     */
    public String generateReport(String month) {
        // cr-java-0069: paymentApi is now externalized — not hard-coded in source.
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Computes a SHA-256 hex digest of the input string.
     * Replaces the broken MD5 hash (cr-java-0090 / sec-weak-hash-001).
     * Authentication itself is delegated to Azure Active Directory via Spring Security.
     *
     * @param input the string to hash
     * @return hex-encoded SHA-256 digest
     */
    private String sha256Hash(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
