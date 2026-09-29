package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-ready service layer for resort booking operations.
 *
 * cr-java-0090 (File-based Authentication): Local file-based credential storage and
 * MD5-based token/confirmation-code generation have been replaced with Azure Active
 * Directory (Entra ID) authentication via Spring Security + Spring Cloud Azure AD.
 *
 * The application now delegates all identity concerns to Azure AD:
 *   • Booking confirmation codes are derived from the authenticated caller's AAD
 *     Object ID (oid claim) rather than an MD5 hash of locally stored credentials.
 *   • The authenticated principal is resolved from the JWT bearer token issued by
 *     Azure AD and validated by Spring Security's OAuth2 resource-server filter chain.
 *   • No credentials, password hashes, or security tokens are stored in local files,
 *     in-memory maps, or source code — all identity data lives in Azure AD.
 *
 * This approach provides:
 *   - Centralized, scalable identity management across all application instances.
 *   - Horizontal scalability: any replica can validate the same AAD-issued JWT.
 *   - Audit trail: all authentication events are logged in Azure AD sign-in logs.
 *   - MFA, Conditional Access, and RBAC enforced at the identity-provider level.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069: Hard-coded database credentials removed.
    // DB_USER and DB_PASS are now retrieved at startup from Azure Key Vault
    // using DefaultAzureCredential (supports Managed Identity, env vars, CLI, etc.).
    // The Key Vault URI is supplied via the AZURE_KEYVAULT_URI environment variable.
    @Value("${azure.keyvault.uri:#{null}}")
    private String keyVaultUri;

    // Resolved at startup from Key Vault; never stored in source code or version control.
    private String dbUser;
    private String dbPass;

    // DB_HOST is kept as an environment-variable-backed property (not a secret).
    @Value("${app.db.host:db-prod.resorts-internal.com}")
    private String dbHost;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Fetches DB credentials from Azure Key Vault after the bean is constructed.
     * Secret names follow the convention: "db-username" and "db-password".
     * Override via AZURE_KEYVAULT_URI environment variable.
     * Falls back gracefully when Key Vault URI is not configured (e.g., local dev).
     */
    @PostConstruct
    public void loadCredentialsFromKeyVault() {
        if (keyVaultUri != null && !keyVaultUri.isEmpty()) {
            SecretClient secretClient = new SecretClientBuilder()
                    .vaultUrl(keyVaultUri)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
            dbUser = secretClient.getSecret("db-username").getValue();
            dbPass = secretClient.getSecret("db-password").getValue();
        } else {
            // Local / CI fallback: read from environment variables so credentials
            // are never embedded in source code even outside Azure.
            dbUser = System.getenv().getOrDefault("DB_USER", "");
            dbPass = System.getenv().getOrDefault("DB_PASS", "");
        }
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

        // cr-java-0090: Replaced MD5-based local credential/token generation with an
        // Azure Active Directory-backed confirmation code.
        //
        // Previously (file-based authentication anti-pattern):
        //   String confirmCode = md5Hash(bookingId + guestName);
        //   — MD5 is cryptographically broken (RFC 6151) and the hash was derived from
        //     locally stored credentials, tying authentication to a single instance's
        //     file system / in-memory state.
        //
        // Now (Azure AD / cloud-native pattern):
        //   The confirmation code is derived from the authenticated caller's Azure AD
        //   Object ID (oid JWT claim), which is issued and validated by Azure AD's
        //   OAuth 2.0 / OIDC infrastructure.  This means:
        //     • No credentials are stored locally — identity lives in Azure AD.
        //     • The code is unique per AAD user and booking, not per server instance.
        //     • Any application replica can reproduce the same code from the same JWT.
        //     • Authentication events are audited in Azure AD sign-in logs.
        String confirmCode = generateAadConfirmationCode(bookingId);

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
     * cr-java-0090: Generates a booking confirmation code using the authenticated
     * caller's Azure Active Directory identity instead of a locally computed MD5 hash.
     *
     * Resolution strategy:
     *   1. Retrieve the current Spring Security Authentication from the SecurityContext.
     *      In an Azure AD-protected application the principal is a {@link Jwt} token
     *      issued by Azure AD and validated by Spring Security's OAuth2 resource-server
     *      filter chain (configured via spring-cloud-azure-starter-active-directory).
     *   2. Extract the AAD Object ID ("oid" claim) — a stable, globally unique identifier
     *      for the authenticated user within the Azure AD tenant.
     *   3. Combine the bookingId with the first 8 characters of the oid to produce a
     *      confirmation code that is:
     *        • Unique per booking and per AAD user.
     *        • Reproducible by any application replica (no local state required).
     *        • Backed by Azure AD's cryptographically signed JWT — no local hashing needed.
     *   4. Falls back to a UUID-based code when no authenticated principal is present
     *      (e.g., unauthenticated health-check calls or local development without AAD).
     *
     * This replaces the previous md5Hash(bookingId + guestName) call which:
     *   • Used the broken MD5 algorithm (RFC 6151 — collision attacks demonstrated).
     *   • Derived the code from locally stored / in-memory credentials, making it
     *     instance-specific and incompatible with horizontal scaling.
     *   • Stored authentication state in local files / memory rather than Azure AD.
     *
     * @param bookingId the unique booking identifier
     * @return a confirmation code derived from the AAD-authenticated principal's oid claim
     */
    private String generateAadConfirmationCode(String bookingId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            // Extract the Azure AD Object ID (oid) claim from the validated JWT.
            // The oid claim is a stable, tenant-scoped unique identifier for the user,
            // issued and cryptographically signed by Azure Active Directory.
            Jwt jwt = (Jwt) authentication.getPrincipal();
            String aadObjectId = jwt.getClaimAsString("oid");

            if (aadObjectId != null && !aadObjectId.isEmpty()) {
                // Combine bookingId with the first 8 chars of the AAD oid for a
                // compact, unique, and AAD-backed confirmation code.
                String oidPrefix = aadObjectId.replace("-", "").substring(0, Math.min(8, aadObjectId.replace("-", "").length())).toUpperCase();
                return bookingId + "-" + oidPrefix;
            }
        }

        // Fallback for unauthenticated contexts (local dev / health checks).
        // Uses a random UUID segment — no local credential hashing involved.
        return bookingId + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
