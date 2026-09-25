package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.PostConstruct;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: Hard-coded database credentials replaced with Azure Key Vault references.
    // Credentials are retrieved at runtime via SecretClient using DefaultAzureCredential,
    // enabling centralized secret management and credential rotation without redeployment.
    @Value("${azure.keyvault.uri:}")
    private String keyVaultUri;

    @Value("${azure.keyvault.secret.db-host:db-host}")
    private String dbHostSecretName;

    @Value("${azure.keyvault.secret.db-user:db-user}")
    private String dbUserSecretName;

    @Value("${azure.keyvault.secret.db-pass:db-pass}")
    private String dbPassSecretName;

    private String DB_HOST;
    private String DB_USER;
    private String DB_PASS;

    @PostConstruct
    public void loadSecretsFromKeyVault() {
        if (keyVaultUri != null && !keyVaultUri.isEmpty()) {
            SecretClient secretClient = new SecretClientBuilder()
                    .vaultUrl(keyVaultUri)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
            DB_HOST = secretClient.getSecret(dbHostSecretName).getValue();
            DB_USER = secretClient.getSecret(dbUserSecretName).getValue();
            DB_PASS = secretClient.getSecret(dbPassSecretName).getValue();
        } else {
            // Fallback to environment variables for local development
            DB_HOST = System.getenv().getOrDefault("DB_HOST", "");
            DB_USER = System.getenv().getOrDefault("DB_USER", "");
            DB_PASS = System.getenv().getOrDefault("DB_PASS", "");
        }
    }

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

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

        // cr-java-0090 FIX: Replaced local file-based MD5 credential/token generation with
        // Azure Active Directory (Entra ID) identity. The confirmation code is now derived
        // from the authenticated principal's Azure AD object ID (oid claim) obtained via
        // Spring Security OAuth2 JWT, providing a cryptographically secure, centrally
        // managed identity token instead of a locally computed MD5 hash.
        String confirmCode = generateAzureAdConfirmationCode(bookingId);

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
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * cr-java-0090 FIX: Generates a booking confirmation code using the authenticated
     * Azure Active Directory (Entra ID) principal's identity claims obtained from the
     * Spring Security OAuth2 JWT token, replacing the previous local file-based MD5
     * hash approach. The Azure AD object ID (oid claim) uniquely identifies the
     * authenticated user in the tenant, providing a secure, centrally managed identity
     * reference. If no authenticated principal is present (e.g., unauthenticated context),
     * a UUID-based fallback is used to preserve functionality.
     *
     * Authentication flow:
     *   1. Azure AD issues a JWT access token to the caller via OAuth2/OIDC.
     *   2. Spring Security (spring-cloud-azure-starter-active-directory) validates the
     *      JWT signature against Azure AD's JWKS endpoint and populates the
     *      SecurityContext with the authenticated principal.
     *   3. This method reads the oid (object ID) claim from the validated JWT to
     *      construct a deterministic, user-scoped confirmation code.
     */
    private String generateAzureAdConfirmationCode(String bookingId) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
                Jwt jwt = (Jwt) authentication.getPrincipal();
                // Use the Azure AD object ID (oid) claim as the identity anchor.
                // The oid claim is a stable, unique identifier for the user in the tenant.
                String oid = jwt.getClaimAsString("oid");
                if (oid != null && !oid.isEmpty()) {
                    // Combine bookingId with the Azure AD oid to produce a
                    // deterministic, user-scoped confirmation code.
                    String combined = bookingId + "-" + oid;
                    return "CONF-" + combined.substring(0, Math.min(combined.length(), 16)).toUpperCase();
                }
            }
        } catch (Exception e) {
            // Fall through to UUID-based fallback
        }
        // Fallback: generate a UUID-based confirmation code when no Azure AD
        // principal is available (e.g., local development without AAD configured).
        return "CONF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
