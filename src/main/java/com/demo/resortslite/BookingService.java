package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service for resort booking operations.
 * Hard-coded database credentials replaced with Azure Key Vault references (cr-java-0069).
 * File-based authentication replaced with Azure Active Directory via Spring Security (cr-java-0090).
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069: Azure Key Vault endpoint loaded from environment variable.
    // All secrets (DB credentials, payment API URL) are retrieved at runtime from Key Vault
    // using DefaultAzureCredential — no hard-coded credentials in source code.
    @Value("${AZURE_KEY_VAULT_ENDPOINT:}")
    private String keyVaultEndpoint;

    // cr-java-0069: DB_HOST, DB_USER, DB_PASS hard-coded constants removed.
    // Credentials are now fetched from Azure Key Vault at runtime.

    // cr-java-0069: PAYMENT_API hard-coded URL removed.
    // Payment API endpoint is now fetched from Azure Key Vault at runtime.

    /**
     * Retrieves a secret value from Azure Key Vault.
     * Used to replace all hard-coded credentials (cr-java-0069).
     *
     * @param secretName the name of the secret in Key Vault
     * @return the secret value, or empty string if Key Vault is not configured
     */
    private String getSecret(String secretName) {
        if (keyVaultEndpoint == null || keyVaultEndpoint.isEmpty()) {
            // Fall back to environment variable with the same name
            String envValue = System.getenv(secretName.toUpperCase().replace("-", "_"));
            return envValue != null ? envValue : "";
        }
        SecretClient secretClient = new SecretClientBuilder()
                .vaultUrl(keyVaultEndpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        return secretClient.getSecret(secretName).getValue();
    }

    /**
     * Creates a new booking record.
     * Hard-coded DB credentials replaced with Azure Key Vault (cr-java-0069).
     *
     * @param guestName guest's full name
     * @param roomType  type of room requested
     * @param checkIn   check-in date string
     * @param checkOut  check-out date string
     * @return booking details map
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterized query — preserves SQL injection fix from existing code
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        String confirmCode = md5Hash(bookingId + guestName);

        // cr-java-0069: DB_HOST hard-coded value removed; host info sourced from Key Vault
        String dbHost = getSecret("db-host");

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", dbHost.isEmpty() ? "[configured-via-key-vault]" : dbHost);
        return booking;
    }

    /**
     * Retrieves a booking by its ID.
     *
     * @param bookingId the booking identifier
     * @return booking details map
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterized query — prevents SQL injection
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
     * Calculates the room price based on type, nights, season, and loyalty tier.
     *
     * @param roomType the type of room
     * @param nights   number of nights
     * @param season   season code (PEAK, OFF, or standard)
     * @param loyalty  loyalty tier (GOLD, PLATINUM, DIAMOND, or standard)
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
        if (nights >= 7) { basePrice = basePrice * 0.95; }
        else if (nights >= 14) { basePrice = basePrice * 0.90; }
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room type is available.
     *
     * @param roomType the room type to check
     * @return true if the room type is valid and available
     */
    public boolean isRoomAvailable(String roomType) {
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    /**
     * Generates a report for the given month.
     * Payment API URL is now retrieved from Azure Key Vault (cr-java-0069).
     *
     * @param month the month for the report
     * @return report generation status message
     */
    public String generateReport(String month) {
        // cr-java-0069: PAYMENT_API hard-coded URL replaced with Key Vault secret retrieval
        String paymentApi = getSecret("payment-api-url");
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Returns the currently authenticated user's name from Azure AD via Spring Security.
     * Replaces file-based authentication (cr-java-0090).
     *
     * @return the authenticated principal name, or "anonymous" if not authenticated
     */
    public String getAuthenticatedUser() {
        // cr-java-0090: File-based credential storage replaced with Azure Active Directory
        // authentication via Spring Security. The authenticated principal is obtained from
        // the SecurityContext populated by the Azure AD Spring Boot Starter.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            return authentication.getName();
        }
        return "anonymous";
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
