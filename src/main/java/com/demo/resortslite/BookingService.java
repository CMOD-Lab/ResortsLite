package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service for booking operations.
 * Hard-coded database credentials replaced with Azure Key Vault references (cr-java-0069).
 * File-based authentication replaced with Azure Active Directory via Spring Security (cr-java-0090).
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069: Azure Key Vault URI injected from environment variable — no credentials in source.
    @Value("${azure.keyvault.uri:${AZURE_KEYVAULT_URI:}}")
    private String keyVaultUri;

    // cr-java-0069: Secret names are configuration values, not the secrets themselves.
    @Value("${azure.keyvault.secret.db-user:db-user}")
    private String dbUserSecretName;

    @Value("${azure.keyvault.secret.db-pass:db-pass}")
    private String dbPassSecretName;

    // cr-java-0069: Payment API URL externalized — no hard-coded infrastructure hostname.
    @Value("${app.payment.endpoint:${PAYMENT_ENDPOINT:https://payment-svc.internal/payments/charge}}")
    private String paymentApi;

    // Resolved at startup from Azure Key Vault — never stored in source code.
    private String dbUser;
    private String dbPass;

    /**
     * Resolves database credentials from Azure Key Vault at application startup.
     * Replaces hard-coded DB_USER and DB_PASS constants (cr-java-0069).
     */
    @PostConstruct
    public void resolveSecretsFromKeyVault() {
        if (keyVaultUri != null && !keyVaultUri.isEmpty()) {
            // cr-java-0069: Use DefaultAzureCredential (managed identity / service principal)
            // to authenticate with Azure Key Vault — no credentials embedded in code.
            SecretClient secretClient = new SecretClientBuilder()
                    .vaultUrl(keyVaultUri)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();

            this.dbUser = secretClient.getSecret(dbUserSecretName).getValue();
            this.dbPass = secretClient.getSecret(dbPassSecretName).getValue();
        }
        // If Key Vault URI is not configured (e.g., local dev), credentials remain null
        // and the application relies on the datasource configured in application.properties.
    }

    /**
     * Creates a new booking record.
     *
     * @param guestName the guest's name
     * @param roomType  the room type
     * @param checkIn   the check-in date
     * @param checkOut  the check-out date
     * @return a map containing booking details
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterized query — prevents SQL injection
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // SHA-256 confirmation code — replaces broken MD5 hash
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
     * Retrieves a booking by its identifier.
     *
     * @param bookingId the booking identifier
     * @return a map containing booking details or an error entry
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
     * Calculates the total room price based on type, nights, season, and loyalty tier.
     *
     * @param roomType the room type
     * @param nights   the number of nights
     * @param season   the season (PEAK / OFF / standard)
     * @param loyalty  the loyalty tier (GOLD / PLATINUM / DIAMOND / standard)
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
     *
     * @param month the month for the report
     * @return a status message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Computes a SHA-256 hash of the input string.
     * Replaces the broken MD5 hash used previously.
     *
     * @param input the string to hash
     * @return hex-encoded SHA-256 digest
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

    // cr-java-0090: File-based authentication removed. Authentication is now handled by
    // Azure Active Directory (Entra ID) via Spring Security Azure AD integration configured
    // in application.properties and the spring-cloud-azure-starter-active-directory dependency.
    // The former loadUserFromFile() method and any local credential file reads have been
    // eliminated; all identity verification is delegated to Azure AD / MSAL.
}
