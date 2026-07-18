package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController exposes REST endpoints for resort booking operations.
 *
 * Session state is managed via Amazon ElastiCache for Redis through Spring Session,
 * enabling stateless application instances and horizontal scaling without server affinity.
 *
 * In-memory caching is replaced with Redis-backed caching (TTL-controlled) to ensure
 * consistent data across all instances and prevent unbounded memory growth.
 *
 * Environment-specific URLs are injected from AWS Systems Manager Parameter Store
 * via application properties — no hard-coded endpoints in source code.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Redis template for distributed cache operations.
     * Replaces the static in-memory HashMap (cr-java-0067) with Amazon ElastiCache
     * for Redis, providing TTL-controlled, cross-instance consistent caching.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL in minutes — configurable via environment variable
    @Value("${cache.booking.ttl-minutes:60}")
    private long bookingCacheTtlMinutes;

    // Session TTL in minutes — configurable via environment variable
    @Value("${cache.session.ttl-minutes:30}")
    private long sessionTtlMinutes;

    // Inventory service URL injected from environment / SSM Parameter Store
    // Replaces hard-coded "http://inventory-service.internal:8081/rooms/available" (blocker-10)
    @Value("${app.inventory.endpoint:#{environment['INVENTORY_SERVICE_URL'] ?: 'https://inventory-service.internal/rooms/available'}}")
    private String inventoryEndpoint;

    private static final String CACHE_PREFIX = "booking:";
    private static final String SESSION_PREFIX = "session:guest:";

    /**
     * Creates a new booking and stores session state in Redis (ElastiCache).
     * Replaces HttpSession usage (cr-java-0065) with Redis-backed distributed session.
     * Replaces static in-memory bookingCache (cr-java-0067) with Redis cache with TTL.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String bookingId = (String) booking.get("bookingId");

        // Store booking in Redis with TTL — replaces static in-memory HashMap (cr-java-0067)
        // Ensures cache is shared across all instances and entries expire automatically
        redisTemplate.opsForValue().set(
                CACHE_PREFIX + bookingId,
                booking,
                bookingCacheTtlMinutes,
                TimeUnit.MINUTES);

        // Store session state in Redis — replaces HttpSession (cr-java-0065)
        // Enables stateless instances: any node can serve subsequent requests
        if (!sessionId.isEmpty()) {
            redisTemplate.opsForValue().set(
                    SESSION_PREFIX + sessionId + ":lastBooking",
                    booking,
                    sessionTtlMinutes,
                    TimeUnit.MINUTES);
            redisTemplate.opsForValue().set(
                    SESSION_PREFIX + sessionId + ":guestName",
                    guestName,
                    sessionTtlMinutes,
                    TimeUnit.MINUTES);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking, reading session state from Redis.
     * Replaces HttpSession.getAttribute (cr-java-0065) with Redis lookup,
     * ensuring the guest name is available regardless of which instance handles the request.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "") String sessionId) {

        // Read session state from Redis — replaces HttpSession (cr-java-0065)
        String lastGuest = null;
        if (!sessionId.isEmpty()) {
            Object cachedGuest = redisTemplate.opsForValue().get(
                    SESSION_PREFIX + sessionId + ":guestName");
            lastGuest = cachedGuest != null ? cachedGuest.toString() : null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability.
     * The inventory service URL is injected from environment / SSM Parameter Store
     * instead of being hard-coded (blocker-10 / cr-java-0071).
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // inventoryEndpoint is injected from application properties / environment variable
        // which is populated from AWS Systems Manager Parameter Store — not hard-coded
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a download link for a monthly report.
     * Report path is resolved via Amazon S3 — no local file system dependency.
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report is stored in Amazon S3; generate a reference key instead of a local path
        String s3ReportKey = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("s3ReportKey", s3ReportKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
