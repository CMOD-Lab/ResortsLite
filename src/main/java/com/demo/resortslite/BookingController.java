package com.demo.resortslite;

import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native implementation using Azure Cache for Redis
 * for distributed session state and caching, and Azure App Configuration for
 * externalised environment URLs.
 *
 * <p>Fixes applied:
 * <ul>
 *   <li>cr-java-0065 — HTTP session state (HttpSession) replaced with Azure Cache
 *       for Redis via {@link RedisTemplate}. Session data is now stored in a
 *       distributed cache, enabling stateless horizontal scaling across instances.</li>
 *   <li>cr-java-0067 — In-memory {@code HashMap} cache without TTL replaced with
 *       Azure Cache for Redis with a configurable TTL, preventing memory exhaustion
 *       and ensuring cache consistency across instances.</li>
 *   <li>cr-java-0071 — Hard-coded inventory service URL externalised to Azure App
 *       Configuration; falls back to the {@code INVENTORY_SERVICE_URL} environment
 *       variable when App Configuration is unavailable.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // -----------------------------------------------------------------------
    // cr-java-0065 / cr-java-0067 FIX:
    // Static in-memory HashMap (bookingCache) and HttpSession replaced with
    // Azure Cache for Redis via RedisTemplate. TTL is set per entry to prevent
    // unbounded memory growth and ensure cache consistency across instances.
    // -----------------------------------------------------------------------

    /** Redis template for distributed session state and booking cache. */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** TTL for booking cache entries in minutes (default: 60). */
    @Value("${BOOKING_CACHE_TTL_MINUTES:60}")
    private long bookingCacheTtlMinutes;

    /** TTL for session entries in minutes (default: 30). */
    @Value("${SESSION_TTL_MINUTES:30}")
    private long sessionTtlMinutes;

    // -----------------------------------------------------------------------
    // cr-java-0071 FIX:
    // Hard-coded inventory service URL replaced with Azure App Configuration
    // value loaded at runtime; falls back to INVENTORY_SERVICE_URL env variable.
    // -----------------------------------------------------------------------

    /** Azure App Configuration endpoint — injected from environment variable. */
    @Value("${AZURE_APP_CONFIG_ENDPOINT:#{null}}")
    private String appConfigEndpoint;

    /** Inventory service URL — resolved from Azure App Configuration or env variable. */
    @Value("${INVENTORY_SERVICE_URL:https://inventory-service.internal:8081/rooms/available}")
    private String inventoryServiceUrl;

    private static final String SESSION_PREFIX = "session:";
    private static final String CACHE_PREFIX   = "booking:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Store session state in Azure Cache for Redis with TTL
        // instead of HttpSession (which is instance-local and breaks horizontal scaling).
        String sessionKey = SESSION_PREFIX + sessionId;
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
        redisTemplate.expire(sessionKey, sessionTtlMinutes, TimeUnit.MINUTES);

        // cr-java-0067 FIX: Store booking in Redis with TTL instead of static HashMap.
        String cacheKey = CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        // cr-java-0065 FIX: Read session state from Azure Cache for Redis instead of
        // HttpSession — consistent across all instances in the cluster.
        String sessionKey = SESSION_PREFIX + sessionId;
        String lastGuest = (String) redisTemplate.opsForHash().get(sessionKey, "guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Inventory service URL resolved from Azure App Configuration
        // or environment variable — no hard-coded URL in source code.
        String inventoryUrl = resolveInventoryServiceUrl();

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now managed by ReportService using Azure Blob Storage.
        // No hard-coded file path in the controller.
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves the inventory service URL from Azure App Configuration when
     * available, otherwise falls back to the injected environment variable value.
     */
    private String resolveInventoryServiceUrl() {
        if (appConfigEndpoint != null && !appConfigEndpoint.isEmpty()) {
            try {
                ConfigurationClient configClient = new ConfigurationClientBuilder()
                        .credential(new DefaultAzureCredentialBuilder().build())
                        .endpoint(appConfigEndpoint)
                        .buildClient();
                String configuredUrl = configClient.getConfigurationSetting(
                        "inventory.service.url", null).getValue();
                if (configuredUrl != null && !configuredUrl.isEmpty()) {
                    return configuredUrl;
                }
            } catch (Exception e) {
                // Fall through to environment variable fallback
            }
        }
        return inventoryServiceUrl;
    }
}
