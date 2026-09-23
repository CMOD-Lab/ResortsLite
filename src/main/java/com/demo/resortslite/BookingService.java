package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${azure.keyvault.url:}")
    private String keyVaultUrl;

    @Value("${app.db.host-secret-name:db-host}")
    private String dbHostSecretName;

    @Value("${app.db.user-secret-name:db-user}")
    private String dbUserSecretName;

    @Value("${app.db.password-secret-name:db-password}")
    private String dbPasswordSecretName;

    @Value("${app.payment.api.url:https://payments.internal/payments/charge}")
    private String paymentApi;

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        jdbcTemplate.update(
                "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)",
                bookingId, guestName, roomType, checkIn, checkOut
        );

        String confirmCode = hashConfirmationCode(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", getSecretValue(dbHostSecretName, "db-host-not-configured"));
        booking.put("dbUser", getSecretValue(dbUserSecretName, "db-user-not-configured"));
        booking.put("dbPasswordConfigured", !"db-password-not-configured".equals(getSecretValue(dbPasswordSecretName, "db-password-not-configured")));
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", bookingId);
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
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    private String hashConfirmationCode(String input) {
        return new BCryptPasswordEncoder().encode(input);
    }

    private String getSecretValue(String secretName, String fallbackValue) {
        if (keyVaultUrl == null || keyVaultUrl.trim().isEmpty()) {
            return fallbackValue;
        }
        try {
            SecretClient client = new SecretClientBuilder()
                    .vaultUrl(keyVaultUrl)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
            return client.getSecret(secretName).getValue();
        } catch (Exception ex) {
            return fallbackValue;
        }
    }
}
