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
 * Changes applied for cloud readiness:
 *  - Blocker-13/14/15/16/17 (cr-java-0065): HttpSession replaced with Amazon ElastiCache
 *    for Redis via Spring Session / RedisTemplate for distributed, stateless session management.
 *  - Blocker-20 (cr-java-0067): In-memory HashMap cache replaced with RedisTemplate with TTL
 *    to ensure controlled expiration and consistency across all instances.
 *  - Blocker-10 (cr-java-0071): Hard-coded inventory service URL replaced with value injected
 *    from AWS SSM Parameter Store via @Value / environment variable.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Distributed cache backed by Amazon ElastiCache for Redis.
     * Replaces the instance-local static HashMap (blocker-20, cr-java-0067).
     * TTL is applied on every put to prevent unbounded memory growth.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** Cache TTL in minutes — configurable via environment variable. */
    @Value("${BOOKING_CACHE_TTL_MINUTES:60}")
    private long bookingCacheTtlMinutes;

    /**
     * Inventory service URL — injected from environment variable / SSM Parameter Store.
     * Replaces the hard-coded http://inventory-service.internal:8081/rooms/available URL
     * (blocker-10, cr-java-0071).
     * Set INVENTORY_SERVICE_URL in ECS task definition or Elastic Beanstalk environment.
     */
    @Value("${INVENTORY_SERVICE_URL:https://inventory-service.internal/rooms/available}")
    private String inventoryServiceUrl;

    /** Redis key prefix for booking session data. */
    private static final String SESSION_KEY_PREFIX = "session:booking:";

    /** Redis key prefix for booking cache entries. */
    private static final String CACHE_KEY_PREFIX = "cache:booking:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Blocker-14/15 (cr-java-0065): Store session state in Redis (ElastiCache) instead of
        // HttpSession. This ensures state is visible to all instances behind the AWS ALB.
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
        redisTemplate.expire(sessionKey, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        // Blocker-20 (cr-java-0067): Store booking in Redis with TTL instead of local HashMap.
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
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        // Blocker-16 (cr-java-0065): Read session state from Redis instead of HttpSession.
        // Returns consistent data regardless of which instance handles the request.
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
        // Blocker-10 (cr-java-0071): Use environment-injected URL from SSM Parameter Store
        // instead of the hard-coded http://inventory-service.internal:8081/rooms/available.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryServiceUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now an S3 key — no local file system dependency.
        // The actual S3 URL is constructed by ReportService using SSM Parameter Store.
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
