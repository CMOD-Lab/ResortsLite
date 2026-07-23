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

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Blocker-8, Blocker-9 (cr-java-0069): Hard-coded DB credentials removed from source code.
    // Credentials are now retrieved at runtime from Azure Key Vault using DefaultAzureCredential,
    // enabling automated rotation without redeployment and preventing credential exposure.
    @Value("${azure.keyvault.uri:${AZURE_KEYVAULT_URI:}}")
    private String keyVaultUri;

    private static final String DB_HOST_SECRET_NAME = "db-host";
    private static final String DB_USER_SECRET_NAME = "db-username";
    private static final String DB_PASS_SECRET_NAME = "db-password";

    private static final String PAYMENT_API_SECRET_NAME = "payment-api-url";

    /**
     * Returns a SecretClient connected to Azure Key Vault using DefaultAzureCredential.
     * This supports Managed Identity, environment variables, and other Azure auth mechanisms.
     */
    private SecretClient getSecretClient() {
        return new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
    }

    /**
     * Retrieves a secret value from Azure Key Vault by name.
     * Replaces hard-coded credential constants (blockers 8-9, cr-java-0069).
     */
    private String getSecret(String secretName) {
        return getSecretClient().getSecret(secretName).getValue();
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Blocker-8/9 (cr-java-0069): DB credentials retrieved from Azure Key Vault at runtime,
        // not hard-coded. DB_HOST is also sourced from Key Vault.
        String dbHost = getSecret(DB_HOST_SECRET_NAME);

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
        booking.put("dbHost", dbHost);
        return booking;
    }

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
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        // Blocker-8/9 (cr-java-0069): Payment API URL retrieved from Azure Key Vault,
        // not hard-coded in source code.
        String paymentApi = getSecret(PAYMENT_API_SECRET_NAME);
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
