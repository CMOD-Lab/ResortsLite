package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native implementation with Azure Key Vault secret
 * management and Azure Active Directory (Entra ID) authentication.
 *
 * <p>Fixes applied:
 * <ul>
 *   <li>cr-java-0069 — hard-coded DB credentials (DB_HOST, DB_USER, DB_PASS) removed
 *       from source code and replaced with Azure Key Vault references using
 *       {@link SecretClient} and {@link DefaultAzureCredentialBuilder}.</li>
 *   <li>cr-java-0090 — file-based credential storage replaced with Azure Active
 *       Directory (Entra ID) authentication via Spring Security Azure AD integration;
 *       credentials are no longer read from local files.</li>
 * </ul>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -----------------------------------------------------------------------
    // cr-java-0069 FIX:
    // Hard-coded DB_HOST, DB_USER, DB_PASS constants removed.
    // Credentials are now retrieved at runtime from Azure Key Vault using
    // DefaultAzureCredential (supports Managed Identity, environment variables,
    // and workload identity — no secrets in source code or git history).
    // -----------------------------------------------------------------------

    /** Azure Key Vault URI — injected from the AZURE_KEY_VAULT_URI environment variable. */
    @Value("${AZURE_KEY_VAULT_URI:#{null}}")
    private String keyVaultUri;

    /**
     * Lazily-initialised Key Vault secret client.
     * Credentials are fetched on first use, not at class load time.
     */
    private SecretClient secretClient;

    // -----------------------------------------------------------------------
    // cr-java-0090 FIX:
    // File-based authentication removed. Authentication is now delegated to
    // Azure Active Directory (Entra ID) via Spring Security Azure AD integration
    // configured in application.properties / environment variables.
    // The validateUserCredentials method no longer reads from local files;
    // identity verification is handled by the AAD OAuth2 token pipeline.
    // -----------------------------------------------------------------------

    /**
     * Returns the Azure Key Vault {@link SecretClient}, initialising it on first call.
     * Uses {@link DefaultAzureCredentialBuilder} which supports Managed Identity,
     * environment credentials, and workload identity — no hard-coded secrets required.
     *
     * @return the configured {@link SecretClient}
     * @throws IllegalStateException if AZURE_KEY_VAULT_URI is not configured
     */
    private SecretClient getSecretClient() {
        if (secretClient == null) {
            if (keyVaultUri == null || keyVaultUri.isEmpty()) {
                throw new IllegalStateException(
                        "AZURE_KEY_VAULT_URI environment variable is not set. "
                        + "Configure Azure Key Vault to enable secure credential retrieval.");
            }
            secretClient = new SecretClientBuilder()
                    .vaultUrl(keyVaultUri)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
        }
        return secretClient;
    }

    /**
     * Retrieves the database host from Azure Key Vault.
     * Secret name: {@code db-host}
     *
     * @return the database host value stored in Key Vault
     */
    private String getDbHost() {
        return getSecretClient().getSecret("db-host").getValue();
    }

    /**
     * Retrieves the payment API endpoint from Azure Key Vault.
     * Secret name: {@code payment-api-endpoint}
     *
     * @return the payment API endpoint stored in Key Vault
     */
    private String getPaymentApi() {
        return getSecretClient().getSecret("payment-api-endpoint").getValue();
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // VIOLATION [Security Health / Critical]: SQL query built by string concatenation.
        // An attacker can pass guestName = "'; DROP TABLE bookings; --" to destroy data.
        // Use parameterised queries (JdbcTemplate with '?') to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('" // sql-inject-001
                + bookingId + "', '" + guestName + "', '" + roomType               // sql-inject-001
                + "', '" + checkIn + "', '" + checkOut + "')";                     // sql-inject-001
        jdbcTemplate.execute(sql);

        // VIOLATION [Security Health / High]: MD5 is a broken hash algorithm (RFC 6151).
        // Do not use MD5 for any security-related hashing. Use SHA-256 or bcrypt.
        String confirmCode = md5Hash(bookingId + guestName); // sec-weak-hash-001

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // cr-java-0069: DB host is now retrieved from Azure Key Vault, not hard-coded
        booking.put("dbHost", getDbHost());
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        // bookingId is user-supplied input appended directly into the SQL string.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // VIOLATION [Code Sustainability / High]: High cyclomatic complexity.
    // This method has 9+ decision branches. Automated transformation tools flag methods
    // above complexity threshold as high maintenance risk and transformation blockers.
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

    public boolean isRoomAvailable(String roomType) {
        // VIOLATION [Code Sustainability / Medium]: Duplicated validation logic.
        // Same room type validation is repeated here and in calculateRoomPrice.
        // Should be extracted to a shared RoomType enum or validator.
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE") // dup-logic-001
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) { // dup-logic-001
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        // cr-java-0069: payment API endpoint retrieved from Azure Key Vault
        return "Report generation triggered for: " + month + " via " + getPaymentApi();
    }

    private String md5Hash(String input) { // sec-weak-hash-001
        try {
            MessageDigest md = MessageDigest.getInstance("MD5"); // sec-weak-hash-001
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
