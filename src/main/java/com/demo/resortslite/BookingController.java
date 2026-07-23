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
 * REST controller for booking operations.
 * Session state is externalized to Azure Cache for Redis (cr-java-0065).
 * In-memory cache replaced with Redis-backed distributed cache with TTL (cr-java-0067).
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067: Replaced static in-memory HashMap cache (no TTL) with
    // Redis-backed distributed cache via RedisTemplate. TTL is enforced on every put.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL in minutes — externalized to environment variable
    @Value("${BOOKING_CACHE_TTL_MINUTES:30}")
    private long cacheTtlMinutes;

    // cr-java-0071: Inventory service URL loaded from Azure App Configuration
    @Value("${AZURE_APP_CONFIG_ENDPOINT:}")
    private String appConfigEndpoint;

    // Fallback inventory URL from environment variable (cr-java-0071)
    @Value("${INVENTORY_SERVICE_URL:https://inventory-service.internal:8081/rooms/available}")
    private String inventoryServiceUrl;

    // Redis key prefix for session-like guest data (cr-java-0065)
    private static final String SESSION_KEY_PREFIX = "session:guest:";
    // Redis key prefix for booking cache (cr-java-0067)
    private static final String CACHE_KEY_PREFIX = "cache:booking:";

    /**
     * Creates a new booking and stores state in Azure Cache for Redis.
     * Replaces HttpSession usage (cr-java-0065) and in-memory cache (cr-java-0067).
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065: Store session state in Azure Cache for Redis instead of HttpSession.
        // This enables stateless horizontal scaling across multiple instances.
        if (sessionId != null && !sessionId.isEmpty()) {
            redisTemplate.opsForValue().set(
                    SESSION_KEY_PREFIX + sessionId + ":lastBooking",
                    booking,
                    cacheTtlMinutes, TimeUnit.MINUTES);
            redisTemplate.opsForValue().set(
                    SESSION_KEY_PREFIX + sessionId + ":guestName",
                    guestName,
                    cacheTtlMinutes, TimeUnit.MINUTES);
        }

        // cr-java-0067: Store booking in Redis with TTL instead of unbounded in-memory HashMap.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set(
                CACHE_KEY_PREFIX + bookingId,
                booking,
                cacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns booking status, reading guest context from Azure Cache for Redis.
     * Replaces HttpSession.getAttribute (cr-java-0065).
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        // cr-java-0065: Read session state from Redis instead of HttpSession.
        // Works correctly across all instances in the cluster.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            Object val = redisTemplate.opsForValue().get(
                    SESSION_KEY_PREFIX + sessionId + ":guestName");
            if (val != null) {
                lastGuest = val.toString();
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using the inventory service URL from Azure App Configuration.
     * Replaces hard-coded environment URL (cr-java-0071).
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071: Inventory service URL loaded from Azure App Configuration.
        // Falls back to environment variable INVENTORY_SERVICE_URL if App Configuration
        // endpoint is not set.
        String resolvedInventoryUrl = inventoryServiceUrl;

        if (appConfigEndpoint != null && !appConfigEndpoint.isEmpty()) {
            try {
                ConfigurationClient configClient = new ConfigurationClientBuilder()
                        .credential(new DefaultAzureCredentialBuilder().build())
                        .endpoint(appConfigEndpoint)
                        .buildClient();
                String configuredUrl = configClient.getConfigurationSetting(
                        "inventory.service.url", null).getValue();
                if (configuredUrl != null && !configuredUrl.isEmpty()) {
                    resolvedInventoryUrl = configuredUrl;
                }
            } catch (Exception e) {
                // Fall back to environment variable if App Configuration is unavailable
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", resolvedInventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a report download reference using Azure Blob Storage path.
     * Replaces hard-coded local file path (cr-java-0061).
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // cr-java-0061: Replaced hard-coded /var/legacy/reports/ path with
        // Azure Blob Storage reference via ReportService.
        String blobName = month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("blobName", blobName);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
