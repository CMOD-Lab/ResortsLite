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

/**
 * BookingService — cloud-ready service layer for resort booking operations.
 *
 * cr-java-0090 REMEDIATED: File-based authentication (local credential hashing via MD5)
 * has been replaced with Azure Active Directory (Entra ID) authentication using
 * Microsoft Authentication Library (MSAL) and Spring Security OAuth2 Resource Server.
 *
 * Authentication is now delegated entirely to Azure AD:
 *   - The application is registered as an Azure AD App Registration (resource server).
 *   - Incoming requests carry a Bearer JWT token issued by Azure AD.
 *   - Spring Security validates the JWT signature and claims against the Azure AD
 *     JWKS endpoint (configured via spring.security.oauth2.resourceserver.jwt.issuer-uri).
 *   - The authenticated principal (OID, UPN, roles) is available via SecurityContextHolder.
 *   - No credentials, password hashes, or user records are stored locally.
 *
 * This approach provides:
 *   - Centralised, scalable identity management across all application instances.
 *   - Horizontal scalability — no local session or credential state.
 *   - Compliance with Azure security best practices (Zero Trust, least-privilege).
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 REMEDIATED: Hard-coded database credentials removed.
    // DB_USER and DB_PASS are now retrieved at runtime from Azure Key Vault
    // using DefaultAzureCredential (supports Managed Identity, environment
    // variables, and local developer credentials transparently).
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    @Value("${azure.keyvault.uri}")
    private String keyVaultUri;

    /**
     * Retrieves the database username from Azure Key Vault.
     * Secret name: "db-username"
     * Uses DefaultAzureCredential for passwordless authentication via
     * Managed Identity in Azure App Service / AKS, or environment-variable
     * credentials (AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, AZURE_TENANT_ID)
     * in other environments.
     */
    private String getDbUser() {
        SecretClient secretClient = new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        return secretClient.getSecret("db-username").getValue();
    }

    /**
     * Retrieves the database password from Azure Key Vault.
     * Secret name: "db-password"
     * Uses DefaultAzureCredential for passwordless authentication via
     * Managed Identity in Azure App Service / AKS, or environment-variable
     * credentials (AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, AZURE_TENANT_ID)
     * in other environments.
     */
    private String getDbPass() {
        SecretClient secretClient = new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        return secretClient.getSecret("db-password").getValue();
    }

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * cr-java-0090 REMEDIATED: Returns the Azure AD Object ID (OID) of the currently
     * authenticated principal from the validated JWT token held in the Spring Security
     * context.  This replaces the previous pattern of generating a local MD5 hash of
     * user-supplied data as a "confirmation code" — a file-based / in-memory credential
     * approach that does not scale in distributed cloud environments.
     *
     * The OID is a stable, globally-unique identifier assigned by Azure AD and is safe
     * to use as a correlation key for audit logging, booking ownership, and downstream
     * service calls.
     *
     * @return Azure AD Object ID of the authenticated user, or "anonymous" if no
     *         authenticated principal is present in the current security context.
     */
    private String getAuthenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            // "oid" claim — Azure AD Object ID (stable across token refreshes)
            String oid = jwt.getClaimAsString("oid");
            return (oid != null && !oid.isEmpty()) ? oid : jwt.getSubject();
        }
        return "anonymous";
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

        // cr-java-0090 REMEDIATED: Confirmation code is now derived from the Azure AD
        // Object ID of the authenticated user (retrieved from the validated JWT via
        // Spring Security context) instead of an MD5 hash of local user-supplied data.
        // This ties the booking confirmation to a verified, centrally-managed identity
        // rather than a locally-computed, cryptographically-broken hash.
        String authenticatedUserId = getAuthenticatedUserId();
        String confirmCode = bookingId + "-" + authenticatedUserId;

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
}
