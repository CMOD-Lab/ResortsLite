package com.demo.resortslite;

import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
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
        booking.put("dbHost", getDatabaseHost());
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
        return "Report generation triggered for: " + month + " via " + getPaymentApiUrl();
    }

    public Map<String, String> getDatabaseCredentials() {
        Map<String, String> credentials = new HashMap<>();
        credentials.put("username", getSecret("db-user", "DB_USER"));
        credentials.put("password", getSecret("db-password", "DB_PASS"));
        return credentials;
    }

    public boolean isAzureAdAuthenticationEnabled() {
        return Boolean.parseBoolean(getConfigValue("security.azuread.enabled", "true"));
    }

    private String getDatabaseHost() {
        return getConfigValue("database.host", "db-prod.resorts-internal.com");
    }

    private String getPaymentApiUrl() {
        return getConfigValue("payment.api.url", "https://payments.internal/payments/charge");
    }

    private String getSecret(String secretName, String envFallback) {
        String keyVaultUrl = System.getenv("AZURE_KEY_VAULT_URL");
        if (keyVaultUrl != null && !keyVaultUrl.trim().isEmpty()) {
            try {
                SecretClient secretClient = new SecretClientBuilder()
                        .vaultUrl(keyVaultUrl)
                        .credential(new DefaultAzureCredentialBuilder().build())
                        .buildClient();
                return secretClient.getSecret(secretName).getValue();
            } catch (Exception ignored) {
            }
        }

        String envValue = System.getenv(envFallback);
        if (envValue == null || envValue.trim().isEmpty()) {
            throw new IllegalStateException("Missing required secret: " + secretName);
        }
        return envValue;
    }

    private String getConfigValue(String key, String defaultValue) {
        String envKey = key.toUpperCase().replace('.', '_').replace('-', '_');
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue;
        }

        String connectionString = System.getenv("AZURE_APP_CONFIGURATION_CONNECTION_STRING");
        if (connectionString == null || connectionString.trim().isEmpty()) {
            return defaultValue;
        }

        try {
            ConfigurationClient client = new ConfigurationClientBuilder()
                    .connectionString(connectionString)
                    .buildClient();
            String value = client.getConfigurationSetting(key, null).getValue();
            return value != null && !value.trim().isEmpty() ? value : defaultValue;
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
