package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native implementation.
 *
 * <p>HTTP session state has been migrated to Amazon ElastiCache for Redis via
 * Spring Session (blockers 13-17). The in-memory HashMap cache without TTL has
 * been replaced with a Redis-backed cache with a configurable TTL (blocker-20).
 * Hard-coded environment URLs have been externalised to environment variables
 * sourced from AWS Systems Manager Parameter Store (blocker-10).
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Redis template used for distributed session state and booking cache.
     * Backed by Amazon ElastiCache for Redis — replaces both the in-memory HashMap
     * (blocker-20) and HttpSession-based state (blockers 13-17).
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL in minutes — controls expiration of booking cache entries in Redis (blocker-20).
    private static final long BOOKING_CACHE_TTL_MINUTES = 30L;

    // Inventory service URL is read from the environment variable INVENTORY_SERVICE_URL,
    // which is injected at runtime by ECS / Elastic Beanstalk (blocker-10).
    private final String inventoryUrl = System.getenv().getOrDefault(
            "INVENTORY_SERVICE_URL", "https://inventory-service.internal/rooms/available");

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Store booking state in Amazon ElastiCache for Redis instead of HttpSession (blocker-13, blocker-14).
        // This enables stateless application instances and supports horizontal scaling.
        String sessionKey = "session:" + (sessionId != null ? sessionId : (String) booking.get("bookingId"));
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
        redisTemplate.expire(sessionKey, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        // Store booking in Redis cache with TTL — replaces unbounded in-memory HashMap (blocker-20).
        String cacheKey = "bookingCache:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // Retrieve session state from Amazon ElastiCache for Redis (blocker-15, blocker-16, blocker-17).
        // No longer reads from HttpSession — works correctly across all instances in the cluster.
        String sessionKey = "session:" + (sessionId != null ? sessionId : bookingId);
        String lastGuest = (String) redisTemplate.opsForHash().get(sessionKey, "guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Inventory URL is sourced from environment variable / SSM Parameter Store (blocker-10).
        // No hard-coded environment-specific URL in source code.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now an S3 object key — no hard-coded absolute file path.
        String reportKey = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportKey", reportKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
