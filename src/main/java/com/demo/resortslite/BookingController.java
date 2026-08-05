package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native REST controller.
 *
 * <p><strong>HTTP Session → Amazon ElastiCache for Redis (Spring Session)</strong><br>
 * All {@code HttpSession} usage (blockers 13-17 / cr-java-0065) has been replaced
 * with {@link RedisTemplate} backed by Amazon ElastiCache for Redis via Spring
 * Session.  Session data is now stored in a centralised, distributed Redis cluster
 * so that any application instance in the ECS/EKS cluster can read it, enabling
 * true horizontal scaling and stateless application instances.</p>
 *
 * <p><strong>In-memory cache → Amazon ElastiCache for Redis (blocker-20 / cr-java-0067)</strong><br>
 * The unbounded {@code static HashMap} booking cache has been replaced with a
 * {@link RedisTemplate} cache entry with a configurable TTL (default 30 minutes),
 * ensuring controlled expiration and consistent data across all instances.</p>
 *
 * <p><strong>Hard-coded inventory URL → AWS SSM Parameter Store (blocker-10 / cr-java-0071)</strong><br>
 * The hard-coded {@code http://inventory-service.internal:8081/rooms/available} URL
 * has been replaced with a value injected from the {@code INVENTORY_SERVICE_URL}
 * environment variable, which is populated from AWS SSM Parameter Store at deploy time.</p>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Redis template used for both distributed session state and booking cache.
     * Backed by Amazon ElastiCache for Redis — replaces HttpSession (blockers 13-17)
     * and the unbounded in-memory HashMap cache (blocker-20).
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * TTL (in minutes) for booking cache entries stored in ElastiCache for Redis.
     * Replaces the unbounded in-memory cache without TTL (blocker-20 / cr-java-0067).
     * Env var: BOOKING_CACHE_TTL_MINUTES  →  SSM: /resortsLite/cache/bookingTtlMinutes
     */
    @Value("${booking.cache.ttl-minutes:${BOOKING_CACHE_TTL_MINUTES:30}}")
    private long bookingCacheTtlMinutes;

    /**
     * Inventory service URL — externalised to AWS SSM Parameter Store.
     * Replaces the hard-coded {@code http://inventory-service.internal:8081/rooms/available}
     * URL (blocker-10 / cr-java-0071).
     * Env var: INVENTORY_SERVICE_URL  →  SSM: /resortsLite/inventory/serviceUrl
     */
    @Value("${inventory.service.url:${INVENTORY_SERVICE_URL:https://inventory-service.internal/rooms/available}}")
    private String inventoryServiceUrl;

    // Redis key prefixes
    private static final String SESSION_KEY_PREFIX = "session:";
    private static final String CACHE_KEY_PREFIX   = "bookingCache:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "anonymous") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Replaced HttpSession.setAttribute() with ElastiCache for Redis via RedisTemplate.
        // Session data is now stored in a distributed Redis cluster — visible to ALL
        // application instances, enabling horizontal scaling (blockers 13-17 / cr-java-0065).
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
        redisTemplate.expire(sessionKey, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        // Replaced unbounded static HashMap with ElastiCache for Redis entry with TTL.
        // Ensures controlled expiration and consistent data across instances
        // (blocker-20 / cr-java-0067).
        String cacheKey = CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "anonymous") String sessionId) {

        // Replaced HttpSession.getAttribute() with ElastiCache for Redis lookup.
        // Returns consistent data regardless of which instance handles the request
        // (blocker-17 / cr-java-0065).
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        String lastGuest = (String) redisTemplate.opsForHash().get(sessionKey, "guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Hard-coded inventory URL replaced with SSM Parameter Store value injected
        // via environment variable (blocker-10 / cr-java-0071).
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryServiceUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now an S3 object key — no local file system dependency.
        String reportS3Key = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportS3Key", reportS3Key);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
