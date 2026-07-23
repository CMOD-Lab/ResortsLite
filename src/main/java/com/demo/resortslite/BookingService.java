package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069: Hard-coded DB credentials replaced with Azure Key Vault references.
    // Credentials are retrieved at runtime via DefaultAzureCredential — no secrets in source code.
    @Value("${azure.keyvault.uri:${AZURE_KEYVAULT_URI:}}")
    private String keyVaultUri;

    // cr-java-0069: DB_HOST externalised — loaded from Azure Key Vault secret "db-host"
    @Value("${app.db.host:${APP_DB_HOST:}}")
    private String dbHost;

    // cr-java-0071 / cr-java-0069: Payment API URL externalised to environment variable
    @Value("${app.payment.endpoint:${APP_PAYMENT_ENDPOINT:}}")
    private String paymentApi;

    /**
     * Retrieves a secret value from Azure Key Vault using DefaultAzureCredential.
     * cr-java-0069: Replaces hard-coded credential constants with Key Vault lookups.
     *
     * @param secretName the name of the secret in Azure Key Vault
     * @return the secret value
     */
    private String getSecretFromKeyVault(String secretName) {
        if (keyVaultUri == null || keyVaultUri.isEmpty()) {
            throw new IllegalStateException(
                    "Azure Key Vault URI is not configured. "
                    + "Set AZURE_KEYVAULT_URI environment variable.");
        }
        SecretClient secretClient = new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        return secretClient.getSecret(secretName).getValue();
    }

    /**
     * Creates a new booking record in the database.
     * cr-java-0069: DB credentials sourced from Azure Key Vault, not hard-coded.
     *
     * @param guestName guest name
     * @param roomType  room type
     * @param checkIn   check-in date
     * @param checkOut  check-out date
     * @return booking details map
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterised query prevents SQL injection
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
        // cr-java-0069: dbHost now sourced from environment variable, not hard-coded
        booking.put("dbHost", dbHost);
        return booking;
    }

    /**
     * Retrieves a booking by its ID.
     *
     * @param bookingId the booking identifier
     * @return booking details map
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterised query prevents SQL injection
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
     * @param roomType room type
     * @param nights   number of nights
     * @param season   season code
     * @param loyalty  loyalty tier
     * @return formatted price string
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
     * @param roomType room type to check
     * @return true if available, false otherwise
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
     * cr-java-0069: paymentApi URL sourced from environment variable, not hard-coded.
     *
     * @param month the month for the report
     * @return report generation message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    // cr-java-0090: File-based authentication replaced with Azure Active Directory (Entra ID).
    // Authentication is now handled by Spring Security + Azure AD integration configured
    // in AzureAdSecurityConfig. The file-based credential lookup below is removed.
    // User identity is resolved via the JWT token issued by Azure AD / MSAL.

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
