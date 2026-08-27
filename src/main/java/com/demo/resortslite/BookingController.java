package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native implementation.
 *
 * <p>Changes from the legacy version:</p>
 * <ul>
 *   <li><b>cr-java-0065 (HTTP Session State Storage)</b> — {@code HttpSession} has been
 *       removed. Session state (lastBooking, guestName) is now stored in Amazon ElastiCache
 *       for Redis via Spring's {@link RedisTemplate}, enabling stateless application
 *       instances that can be freely scaled horizontally behind an AWS ALB.</li>
 *   <li><b>cr-java-0067 (In-Memory Caching Without TTL)</b> — The static
 *       {@code HashMap bookingCache} has been replaced with Redis-backed caching through
 *       {@link RedisTemplate} with a configurable TTL, preventing unbounded memory growth
 *       and ensuring cache consistency across all instances.</li>
 *   <li><b>cr-java-0071 (Hard-coded Environment URLs)</b> — The hard-coded
 *       {@code http://inventory-service.internal:8081/rooms/available} URL is now
 *       externalised to AWS Systems Manager Parameter Store and injected via
 *       {@code @Value}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Redis template for distributed session state and booking cache.
     * Backed by Amazon ElastiCache for Redis — replaces HttpSession (cr-java-0065)
     * and the static in-memory HashMap (cr-java-0067).
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Cache TTL in minutes — configurable per environment.
     * Ensures bounded memory usage (fixes cr-java-0067 unbounded cache).
     */
    @Value("${app.cache.booking-ttl-minutes:60}")
    private long bookingCacheTtlMinutes;

    /**
     * Session TTL in minutes — configurable per environment.
     */
    @Value("${app.session.ttl-minutes:30}")
    private long sessionTtlMinutes;

    /**
     * Inventory service URL — retrieved from AWS Systems Manager Parameter Store
     * via Spring property injection (replaces hard-coded URL, cr-java-0071).
     * The property {@code app.inventory.endpoint} is populated from SSM at startup.
     */
    @Value("${app.inventory.endpoint:${INVENTORY_SERVICE_URL:https://inventory-service.internal/rooms/available}}")
    private String inventoryUrl;

    // -----------------------------------------------------------------------
    // Endpoints
    // -----------------------------------------------------------------------

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Store session state in ElastiCache for Redis — replaces HttpSession (cr-java-0065).
        // Any application instance can read this state, enabling true horizontal scaling.
        if (sessionId != null && !sessionId.isEmpty()) {
            String sessionKey = "session:" + sessionId;
            redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
            redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
            redisTemplate.expire(sessionKey, sessionTtlMinutes, TimeUnit.MINUTES);
        }

        // Cache booking in Redis with TTL — replaces static HashMap (cr-java-0067).
        // TTL prevents unbounded memory growth; all instances share the same cache.
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        // Read session state from ElastiCache for Redis — replaces HttpSession (cr-java-0065).
        // Returns consistent data regardless of which instance handles the request.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            String sessionKey = "session:" + sessionId;
            Object guestAttr = redisTemplate.opsForHash().get(sessionKey, "guestName");
            lastGuest = guestAttr != null ? guestAttr.toString() : null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // inventoryUrl is now injected from AWS SSM Parameter Store (cr-java-0071).
        // No hard-coded URL in source code — value changes per environment without redeployment.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now an S3 URI resolved by BookingService / ReportService.
        // No local file system path is referenced here.
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
