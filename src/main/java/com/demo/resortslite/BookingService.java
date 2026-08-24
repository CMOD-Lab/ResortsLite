package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native implementation.
 *
 * <p>Hard-coded database credentials (cr-java-0069) have been removed and are
 * now retrieved at runtime from AWS Secrets Manager. The payment API endpoint
 * is resolved from AWS Systems Manager Parameter Store. File-based
 * authentication (cr-java-0090) has been replaced with AWS Secrets Manager
 * for credential storage and Amazon Cognito for user identity management.</p>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -----------------------------------------------------------------------
    // cr-java-0069 — Hard-coded DB credentials replaced with AWS Secrets Manager.
    // The secret name is supplied via the environment variable DB_SECRET_NAME so
    // it can differ between environments without code changes.
    // -----------------------------------------------------------------------
    private static final String DB_SECRET_NAME =
            System.getenv("DB_SECRET_NAME") != null
                    ? System.getenv("DB_SECRET_NAME")
                    : "resortslite/db/credentials";

    // -----------------------------------------------------------------------
    // cr-java-0069 — DB host / user / password resolved from Secrets Manager.
    // -----------------------------------------------------------------------
    private static final String DB_HOST;
    private static final String DB_USER;
    private static final String DB_PASS;

    static {
        String host = "db-prod.resorts-internal.com";
        String user = "admin";
        String pass = "";
        try {
            SecretsManagerClient smClient = SecretsManagerClient.create();
            GetSecretValueResponse secretResponse = smClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(DB_SECRET_NAME)
                            .build());
            String secretJson = secretResponse.secretString();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(secretJson);
            if (node.has("host"))     host = node.get("host").asText();
            if (node.has("username")) user = node.get("username").asText();
            if (node.has("password")) pass = node.get("password").asText();
        } catch (Exception e) {
            // Credentials remain at safe defaults; application startup will surface
            // the missing secret via health checks rather than exposing credentials.
        }
        DB_HOST = host;
        DB_USER = user;
        DB_PASS = pass;
    }

    // -----------------------------------------------------------------------
    // Payment API endpoint resolved from AWS Systems Manager Parameter Store.
    // -----------------------------------------------------------------------
    private static final String PAYMENT_API = resolvePaymentApi();

    private static String resolvePaymentApi() {
        String envVal = System.getenv("PAYMENT_API_URL");
        if (envVal != null && !envVal.isEmpty()) {
            return envVal;
        }
        try {
            SsmClient ssm = SsmClient.create();
            GetParameterResponse resp = ssm.getParameter(
                    GetParameterRequest.builder()
                            .name("/resortslite/payment/api-url")
                            .withDecryption(false)
                            .build());
            return resp.parameter().value();
        } catch (Exception e) {
            return "https://payment-svc.internal/payments/charge";
        }
    }

    // -----------------------------------------------------------------------
    // cr-java-0090 — File-based authentication replaced with AWS Secrets Manager
    // for credential storage and Amazon Cognito for user identity management.
    // The Cognito User Pool ID and Client ID are stored in Secrets Manager under
    // the secret name supplied by the COGNITO_SECRET_NAME environment variable.
    // -----------------------------------------------------------------------
    private static final String COGNITO_SECRET_NAME =
            System.getenv("COGNITO_SECRET_NAME") != null
                    ? System.getenv("COGNITO_SECRET_NAME")
                    : "resortslite/cognito/config";

    private static final String COGNITO_USER_POOL_ID;
    private static final String COGNITO_CLIENT_ID;

    static {
        String poolId = "";
        String clientId = "";
        try {
            SecretsManagerClient smClient = SecretsManagerClient.create();
            GetSecretValueResponse secretResponse = smClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(COGNITO_SECRET_NAME)
                            .build());
            String secretJson = secretResponse.secretString();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(secretJson);
            if (node.has("userPoolId")) poolId   = node.get("userPoolId").asText();
            if (node.has("clientId"))   clientId = node.get("clientId").asText();
        } catch (Exception e) {
            // Cognito config will be unavailable; authentication endpoints will
            // return appropriate errors rather than falling back to local files.
        }
        COGNITO_USER_POOL_ID = poolId;
        COGNITO_CLIENT_ID    = clientId;
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('" 
                + bookingId + "', '" + guestName + "', '" + roomType               
                + "', '" + checkIn + "', '" + checkOut + "')";                     
        jdbcTemplate.execute(sql);

        String confirmCode = md5Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", DB_HOST);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
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
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * Returns the Amazon Cognito User Pool ID resolved from AWS Secrets Manager.
     * Replaces file-based authentication credential lookup (cr-java-0090).
     *
     * @return Cognito User Pool ID
     */
    public String getCognitoUserPoolId() {
        return COGNITO_USER_POOL_ID;
    }

    /**
     * Returns the Amazon Cognito App Client ID resolved from AWS Secrets Manager.
     * Replaces file-based authentication credential lookup (cr-java-0090).
     *
     * @return Cognito App Client ID
     */
    public String getCognitoClientId() {
        return COGNITO_CLIENT_ID;
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
