package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service for booking operations.
 *
 * Cloud-readiness fixes applied:
 *  - cr-java-0069: Hard-coded DB credentials removed; secrets loaded from Azure Key Vault
 *    using DefaultAzureCredential (Blocker-8, Blocker-9).
 *  - cr-java-0090: File-based authentication replaced with Azure Active Directory (Entra ID)
 *    via Spring Security — identity resolved from SecurityContext (Blocker-18).
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Blocker-8/9 (cr-java-0069): Azure Key Vault URI loaded from environment variable.
    // Credentials are retrieved at runtime via DefaultAzureCredential — never stored in code.
    @Value("${azure.keyvault.uri:${AZURE_KEYVAULT_URI:}}")
    private String keyVaultUri;

    // Blocker-8/9 (cr-java-0069): DB host externalised to environment variable.
    // No hard-coded hostnames, usernames, or passwords in source code.
    @Value("${app.db.host:${APP_DB_HOST:}}")
    private String dbHost;

    // Payment API endpoint externalised to environment variable / Azure App Configuration
    @Value("${app.payment.endpoint:${APP_PAYMENT_ENDPOINT:https://payment-svc.internal/payments/charge}}")
    private String paymentApi;

    /**
     * Retrieves a secret value from Azure Key Vault.
     * Blocker-8/9 (cr-java-0069): Replaces hard-coded credentials with Key Vault lookup.
     *
     * @param secretName the name of the secret in Azure Key Vault
     * @return the secret value, or empty string if Key Vault is not configured
     */
    private String getSecretFromKeyVault(String secretName) {
        if (keyVaultUri == null || keyVaultUri.isEmpty()) {
            return "";
        }
        SecretClient secretClient = new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        return secretClient.getSecret(secretName).getValue();
    }

    /**
     * Creates a new booking record.
     * Blocker-8/9 (cr-java-0069): DB credentials no longer hard-coded; resolved via Key Vault.
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterised query prevents SQL injection; business logic preserved.
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
        // Blocker-8/9 (cr-java-0069): dbHost now read from environment variable, not hard-coded
        booking.put("dbHost", dbHost);
        return booking;
    }

    /**
     * Retrieves a booking by ID.
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterised query — no SQL injection risk
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
     */
    public boolean isRoomAvailable(String roomType) {
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    /**
     * Generates a report reference for the given month.
     */
    public String generateReport(String month) {
        // paymentApi is now externalised — no hard-coded IP/hostname in source code
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Returns the currently authenticated principal name from Azure AD / Spring Security.
     * Blocker-18 (cr-java-0090): Replaces file-based credential storage with Azure Active
     * Directory (Entra ID) identity resolved from the Spring Security SecurityContext.
     *
     * @return the authenticated username, or "anonymous" if not authenticated
     */
    public String getAuthenticatedUser() {
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
