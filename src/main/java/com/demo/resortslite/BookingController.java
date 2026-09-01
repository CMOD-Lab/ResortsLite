package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 REMEDIATION: Static in-memory HashMap cache (bookingCache) removed.
    // Replaced with Azure Cache for Redis via RedisTemplate with a configurable TTL policy.
    // This eliminates instance-local state, prevents unbounded memory growth, and ensures
    // cache consistency across all horizontally-scaled application instances.

    // cr-java-0071 REMEDIATION: Hard-coded inventory service URL replaced with value injected
    // from Azure App Configuration / environment variable. Set APP_INVENTORY_URL (or the
    // app.inventory.url property) in Azure App Service application settings or Azure App
    // Configuration to make this endpoint environment-agnostic.
    @Value("${app.inventory.url:${APP_INVENTORY_URL:http://inventory-service.internal:8081/rooms/available}}")
    private String inventoryServiceUrl;

    // cr-java-0065 REMEDIATION: RedisTemplate replaces HttpSession for externalized,
    // horizontally-scalable session state backed by Azure Cache for Redis.
    // Configure SPRING_REDIS_HOST and SPRING_REDIS_PORT (or SPRING_REDIS_URL) as Azure
    // App Service application settings to point to your Azure Cache for Redis instance.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Session TTL: 30 minutes — matches typical HTTP session timeout.
    private static final long SESSION_TTL_MINUTES = 30L;

    // cr-java-0067 REMEDIATION: Booking cache TTL — 60 minutes.
    // Entries expire automatically, preventing stale data and unbounded memory growth.
    // Adjust BOOKING_CACHE_TTL_MINUTES via Azure App Service application settings if needed.
    @Value("${app.booking.cache.ttl-minutes:${BOOKING_CACHE_TTL_MINUTES:60}}")
    private long bookingCacheTtlMinutes;

    // Redis key prefix for booking cache entries (cr-java-0067 remediation).
    private static final String BOOKING_CACHE_KEY_PREFIX = "bookingCache:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 REMEDIATION: Session state is now stored in Azure Cache for Redis
        // instead of the in-memory HttpSession. Each key is namespaced by sessionId so
        // multiple application instances share the same session store, enabling stateless
        // horizontal scaling and safe failover without sticky sessions.
        String sessionKey = "session:" + sessionId;
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
        redisTemplate.expire(sessionKey, SESSION_TTL_MINUTES, TimeUnit.MINUTES);

        // cr-java-0067 REMEDIATION: Booking is now cached in Azure Cache for Redis with a
        // TTL (bookingCacheTtlMinutes, default 60 min) instead of the removed static
        // in-memory HashMap. The cache is shared across all instances, preventing stale
        // data inconsistencies and unbounded memory growth in cloud environments.
        String bookingCacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(bookingCacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        // cr-java-0065 REMEDIATION: Guest name is now retrieved from Azure Cache for Redis
        // instead of the in-memory HttpSession. This ensures consistent session data is
        // available across all application instances in the cluster.
        String sessionKey = "session:" + sessionId;
        String lastGuest = (String) redisTemplate.opsForHash().get(sessionKey, "guestName");

        // cr-java-0067 REMEDIATION: Booking details are retrieved from Azure Cache for Redis
        // (with TTL) instead of the removed static in-memory HashMap. Falls back to the
        // BookingService if the cache entry has expired or is not present.
        String bookingCacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(bookingCacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 REMEDIATION: Hard-coded URL "http://inventory-service.internal:8081/rooms/available"
        // (line 66 in original source) has been replaced with the externalized field
        // `inventoryServiceUrl` injected via @Value from Azure App Configuration /
        // environment variable APP_INVENTORY_URL. This makes the endpoint environment-agnostic
        // and allows different values per deployment environment (dev / staging / prod)
        // without any code changes.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryServiceUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path. This path does not exist inside a container image. Container images
        // have their own isolated file systems — /var/legacy/reports won't be present.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
