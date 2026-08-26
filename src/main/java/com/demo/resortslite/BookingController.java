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
 * Session state (cr-java-0065) and in-memory cache (cr-java-0067) have been migrated
 * to Google Cloud Memorystore for Redis via Spring Session Data Redis and RedisTemplate,
 * enabling stateless, horizontally-scalable instances.
 *
 * Hard-coded inventory URL (cr-java-0071) has been replaced with an externalized
 * environment-variable-backed property.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // In-memory HashMap cache (cr-java-0067) replaced with Google Cloud Memorystore for
    // Redis via RedisTemplate. TTL is applied on every write to prevent unbounded growth
    // and ensure cache consistency across all distributed instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL in minutes — externalized so it can be tuned per environment.
    @Value("${booking.cache.ttl.minutes:${BOOKING_CACHE_TTL_MINUTES:60}}")
    private long cacheTtlMinutes;

    // Hard-coded inventory URL (cr-java-0071) replaced with externalized property backed
    // by an environment variable; sensitive endpoints can be stored in GCP Secret Manager.
    @Value("${app.inventory.url:${INVENTORY_SERVICE_URL:https://inventory-service.internal:8081/rooms/available}}")
    private String inventoryUrl;

    private static final String BOOKING_CACHE_PREFIX = "booking:";
    private static final String SESSION_LAST_BOOKING_PREFIX = "session:lastBooking:";
    private static final String SESSION_GUEST_PREFIX = "session:guestName:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "anonymous") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Session state migrated to Google Cloud Memorystore for Redis (fixes cr-java-0065,
        // blockers 13-17). TTL ensures entries expire and do not grow unboundedly.
        redisTemplate.opsForValue().set(
                SESSION_LAST_BOOKING_PREFIX + sessionId, booking, cacheTtlMinutes, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(
                SESSION_GUEST_PREFIX + sessionId, guestName, cacheTtlMinutes, TimeUnit.MINUTES);

        // In-memory cache replaced with Redis cache with TTL (fixes cr-java-0067, blocker-20).
        redisTemplate.opsForValue().set(
                BOOKING_CACHE_PREFIX + booking.get("bookingId"), booking,
                cacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "anonymous") String sessionId) {

        // Session state read from Redis (fixes cr-java-0065, blocker-17).
        String lastGuest = (String) redisTemplate.opsForValue().get(
                SESSION_GUEST_PREFIX + sessionId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Hard-coded HTTP URL replaced with externalized HTTPS property (fixes cr-java-0071,
        // blocker-10).
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
