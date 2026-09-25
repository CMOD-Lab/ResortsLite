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

    // cr-java-0067 FIX: Replaced static in-memory HashMap bookingCache (which had no TTL,
    // caused indefinite memory growth, and was invisible to other instances) with
    // Azure Cache for Redis via RedisTemplate. All application instances share the same
    // Redis store, and every cache entry is given an explicit TTL (BOOKING_CACHE_TTL_HOURS)
    // to prevent stale data and memory exhaustion in cloud environments.
    // cr-java-0065 FIX: Replaced instance-local in-memory session storage with
    // Azure Cache for Redis via RedisTemplate. All instances share the same Redis
    // store, enabling stateless horizontal scaling and surviving instance restarts.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0071 FIX: Replaced hard-coded environment URL
    // "http://inventory-service.internal:8081/rooms/available" with a value
    // injected from Azure App Configuration via the externalized property
    // app.inventory.available.url. The URL is sourced from the environment at
    // runtime, enabling environment-agnostic deployments across dev/staging/prod.
    @Value("${app.inventory.available.url:${app.inventory.endpoint}/available}")
    private String inventoryAvailableUrl;

    // Redis key prefix and TTL constants for booking cache entries (cr-java-0067)
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";
    private static final long BOOKING_CACHE_TTL_HOURS = 24L;

    // Redis key prefix and TTL constants for session-scoped data (cr-java-0065)
    private static final String SESSION_KEY_PREFIX = "booking:session:";
    private static final long SESSION_TTL_MINUTES = 30L;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0067 FIX: Booking object is now stored in Azure Cache for Redis with an
        // explicit TTL (BOOKING_CACHE_TTL_HOURS). This replaces the former static HashMap
        // bookingCache which had no expiration policy, grew indefinitely, and was
        // instance-local. The Redis-backed cache is shared across all horizontally-scaled
        // instances, preventing stale data and out-of-memory errors in cloud environments.
        String bookingCacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(bookingCacheKey, booking, BOOKING_CACHE_TTL_HOURS, TimeUnit.HOURS);

        // cr-java-0065 FIX: Booking state and guest name are now stored in Azure Cache
        // for Redis instead of the in-process HTTP session. Using a caller-supplied
        // sessionId (e.g., a JWT sub or a UUID from the client) as the Redis key
        // ensures that any application instance can retrieve the data, eliminating
        // server affinity and supporting auto-scaling and failover transparently.
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
        redisTemplate.expire(sessionKey, SESSION_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        // cr-java-0067 FIX: Booking details are now retrieved from Azure Cache for Redis
        // (with TTL) instead of the former instance-local static HashMap. Any instance
        // in the cluster can serve this request because all instances share the same
        // Redis store. Falls back to the database via bookingService if the cache entry
        // has expired or is not present.
        String bookingCacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(bookingCacheKey);

        // cr-java-0065 FIX: Guest name is now retrieved from Azure Cache for Redis
        // instead of the in-process HTTP session. Any instance in the cluster can
        // serve this request because all instances share the same Redis store.
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        String lastGuest = (String) redisTemplate.opsForHash().get(sessionKey, "guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        // Use cached booking if available; otherwise fall back to the database
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (Line 66): Removed hard-coded environment URL
        // "http://inventory-service.internal:8081/rooms/available".
        // The URL is now resolved at runtime from the externalized property
        // app.inventory.available.url, which is backed by Azure App Configuration,
        // allowing the same artifact to be deployed across all environments without
        // code changes.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryAvailableUrl);
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
