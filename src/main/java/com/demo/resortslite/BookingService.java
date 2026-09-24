package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    private static final String KEY_VAULT_URI_ENV = "AZURE_KEY_VAULT_URI";
    private static final String DB_HOST_SECRET_NAME_ENV = "AZURE_DB_HOST_SECRET_NAME";
    private static final String DB_USER_SECRET_NAME_ENV = "AZURE_DB_USER_SECRET_NAME";
    private static final String DB_PASS_SECRET_NAME_ENV = "AZURE_DB_PASSWORD_SECRET_NAME";
    private static final String DB_HOST_SECRET_DEFAULT = "db-host";
    private static final String DB_USER_SECRET_DEFAULT = "db-user";
    private static final String DB_PASS_SECRET_DEFAULT = "db-password";
    private static final String PAYMENT_API_ENV = "PAYMENT_API_URL";
    private static final String DEFAULT_PAYMENT_API = "https://payments.resorts.internal/payments/charge";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", getSecretValue(DB_HOST_SECRET_NAME_ENV, DB_HOST_SECRET_DEFAULT, "db-host-unavailable"));
        booking.put("dbUser", getSecretValue(DB_USER_SECRET_NAME_ENV, DB_USER_SECRET_DEFAULT, "db-user-unavailable"));
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
        return "Report generation triggered for: " + month + " via " + System.getenv().getOrDefault(PAYMENT_API_ENV, DEFAULT_PAYMENT_API);
    }

    public boolean authenticateWithAzureAd(String bearerToken) {
        return bearerToken != null && bearerToken.startsWith("Bearer ");
    }

    private String getSecretValue(String secretEnvName, String defaultSecretName, String fallbackValue) {
        String keyVaultUri = System.getenv(KEY_VAULT_URI_ENV);
        if (keyVaultUri == null || keyVaultUri.trim().isEmpty()) {
            return fallbackValue;
        }
        try {
            SecretClient secretClient = new SecretClientBuilder()
                    .vaultUrl(keyVaultUri)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
            String secretName = System.getenv().getOrDefault(secretEnvName, defaultSecretName);
            return secretClient.getSecret(secretName).getValue();
        } catch (Exception ex) {
            return fallbackValue;
        }
    }

    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
