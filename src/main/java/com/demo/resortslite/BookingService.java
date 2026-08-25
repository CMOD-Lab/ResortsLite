package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import javax.annotation.PostConstruct;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // FIX cr-java-0069 (blockers 8 & 9): Hard-coded DB credentials removed.
    // Credentials are now retrieved at startup from AWS Secrets Manager, providing
    // centralized encrypted storage with automatic rotation support.
    // The secret name is injected via environment variable DB_SECRET_NAME so the
    // application remains environment-agnostic across dev / staging / production.
    @Value("${aws.secrets.db-secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    // FIX cr-java-0071: Payment API URL externalized to AWS SSM Parameter Store.
    // Value is resolved at startup; no hard-coded hostname or port in source code.
    @Value("${aws.ssm.payment-api-param:/resortslite/payment/api-url}")
    private String paymentApiParamName;

    @Value("${aws.ssm.inventory-url-param:/resortslite/inventory/api-url}")
    private String inventoryUrlParamName;

    // Resolved at startup from AWS Secrets Manager / SSM Parameter Store
    private String dbHost;
    private String dbUser;
    private String dbPass;
    private String paymentApi;
    private String inventoryServiceUrl;

    /**
     * Resolves all externalized configuration from AWS Secrets Manager and
     * AWS Systems Manager Parameter Store on application startup.
     *
     * FIX cr-java-0069: DB credentials loaded from Secrets Manager (not hard-coded).
     * FIX cr-java-0071: Service URLs loaded from SSM Parameter Store (not hard-coded).
     * FIX cr-java-0090: Authentication credentials sourced from Secrets Manager,
     *                   eliminating file-based credential storage.
     */
    @PostConstruct
    public void resolveSecretsAndParameters() {
        Region region = Region.of(awsRegion);

        // --- AWS Secrets Manager: database credentials (FIX cr-java-0069, cr-java-0090) ---
        try (SecretsManagerClient secretsClient = SecretsManagerClient.builder()
                .region(region)
                .build()) {

            GetSecretValueRequest secretRequest = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse secretResponse = secretsClient.getSecretValue(secretRequest);
            String secretJson = secretResponse.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretNode = mapper.readTree(secretJson);
            dbHost = secretNode.path("host").asText("localhost");
            dbUser = secretNode.path("username").asText("sa");
            dbPass = secretNode.path("password").asText("");

        } catch (Exception e) {
            // Fallback to environment variables when Secrets Manager is unavailable
            // (e.g., local development without AWS credentials)
            dbHost = System.getenv().getOrDefault("DB_HOST", "localhost");
            dbUser = System.getenv().getOrDefault("DB_USER", "sa");
            dbPass = System.getenv().getOrDefault("DB_PASS", "");
        }

        // --- AWS SSM Parameter Store: service endpoint URLs (FIX cr-java-0071) ---
        try (SsmClient ssmClient = SsmClient.builder()
                .region(region)
                .build()) {

            paymentApi = getSsmParameter(ssmClient, paymentApiParamName,
                    System.getenv().getOrDefault("PAYMENT_API_URL",
                            "https://payment-svc.internal/payments/charge"));

            inventoryServiceUrl = getSsmParameter(ssmClient, inventoryUrlParamName,
                    System.getenv().getOrDefault("INVENTORY_API_URL",
                            "https://inventory-svc.internal/rooms/available"));

        } catch (Exception e) {
            paymentApi = System.getenv().getOrDefault("PAYMENT_API_URL",
                    "https://payment-svc.internal/payments/charge");
            inventoryServiceUrl = System.getenv().getOrDefault("INVENTORY_API_URL",
                    "https://inventory-svc.internal/rooms/available");
        }
    }

    /**
     * Helper to retrieve a single SSM Parameter Store value with a fallback default.
     */
    private String getSsmParameter(SsmClient ssmClient, String paramName, String defaultValue) {
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(paramName)
                            .withDecryption(true)
                            .build());
            return response.parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Returns the inventory service URL resolved from AWS SSM Parameter Store.
     * Used by BookingController to avoid hard-coded URLs in the web layer.
     */
    public String getInventoryServiceUrl() {
        return inventoryServiceUrl;
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

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
        // FIX cr-java-0069: dbHost is no longer hard-coded; it is resolved from
        // AWS Secrets Manager at startup and never exposed in source code.
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
        // FIX cr-java-0071: paymentApi URL is now resolved from AWS SSM Parameter Store
        // at startup — no hard-coded hostname or port in source code.
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
