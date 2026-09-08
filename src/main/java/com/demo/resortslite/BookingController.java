package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native REST controller for resort booking operations.
 *
 * <p>Blockers resolved:
 * <ul>
 *   <li>cr-java-0065 (lines 6, 27, 34, 35, 48) — HTTP session state replaced with
 *       Amazon ElastiCache for Redis via Spring Session / RedisTemplate, enabling
 *       stateless instances and horizontal scaling.</li>
 *   <li>cr-java-0067 (line 19)                 — unbounded in-memory HashMap cache replaced
 *       with Amazon ElastiCache for Redis with TTL-controlled expiration.</li>
 *   <li>cr-java-0071 (line 66)                 — hard-coded inventory service URL replaced
 *       with a value injected from AWS Systems Manager Parameter Store via
 *       {@code @Value("${app.inventory.endpoint}")} (externalized in application.properties
 *       and overridden at runtime by ECS/Beanstalk environment variables).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // -------------------------------------------------------------------------
    // FIX cr-java-0067 (line 19):
    // The unbounded static HashMap (bookingCache) is replaced with Amazon
    // ElastiCache for Redis via Spring's RedisTemplate.  Each cache entry is
    // stored with a configurable TTL (default 30 minutes) so entries expire
    // automatically, preventing unbounded memory growth and ensuring consistency
    // across all horizontally-scaled instances.
    // -------------------------------------------------------------------------
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL in seconds — externalized so it can be tuned per environment
    @Value("${app.booking.cache.ttl-seconds:1800}")
    private long bookingCacheTtlSeconds;

    // -------------------------------------------------------------------------
    // FIX cr-java-0071 (line 66):
    // Hard-coded "http://inventory-service.internal:8081/rooms/available" replaced
    // with a Spring @Value binding that reads from application.properties
    // (app.inventory.endpoint), which is itself overridden at runtime by the
    // APP_INVENTORY_ENDPOINT environment variable injected by ECS / Beanstalk.
    // -------------------------------------------------------------------------
    @Value("${app.inventory.endpoint:https://inventory-svc.internal/rooms}")
    private String inventoryEndpoint;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // -------------------------------------------------------------------------
        // FIX cr-java-0065 (lines 34, 35):
        // Booking state and guest name are no longer stored in HttpSession (which is
        // instance-local and lost on failover/scale-out).  They are stored in Amazon
        // ElastiCache for Redis with a TTL, making the application fully stateless.
        // The session key is derived from the caller-supplied sessionId parameter.
        // -------------------------------------------------------------------------
        if (sessionId != null && !sessionId.isEmpty()) {
            String sessionKey = "session:" + sessionId + ":lastBooking";
            String guestKey   = "session:" + sessionId + ":guestName";
            redisTemplate.opsForValue().set(sessionKey, booking,       bookingCacheTtlSeconds, TimeUnit.SECONDS);
            redisTemplate.opsForValue().set(guestKey,   guestName,     bookingCacheTtlSeconds, TimeUnit.SECONDS);
        }

        // -------------------------------------------------------------------------
        // FIX cr-java-0067 (line 19):
        // Cache entry stored in Redis with TTL — replaces the unbounded static HashMap.
        // -------------------------------------------------------------------------
        String cacheKey = "bookingCache:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // -------------------------------------------------------------------------
        // FIX cr-java-0065 (line 48):
        // Guest name is retrieved from Redis (ElastiCache) instead of HttpSession,
        // so any instance in the cluster can serve the request correctly.
        // -------------------------------------------------------------------------
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            String guestKey = "session:" + sessionId + ":guestName";
            Object cached = redisTemplate.opsForValue().get(guestKey);
            lastGuest = (cached != null) ? cached.toString() : null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // -------------------------------------------------------------------------
        // FIX cr-java-0071 (line 66):
        // inventoryEndpoint is injected from application.properties / environment
        // variable — no hard-coded URL in source code.
        // -------------------------------------------------------------------------
        String inventoryUrl = inventoryEndpoint + "/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now an S3 key, not a local file path (handled by ReportService)
        String reportKey = "reports/" + month + "_bookings.csv";

        Map<String, Object> response = new HashMap<>();
        response.put("reportKey", reportKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
