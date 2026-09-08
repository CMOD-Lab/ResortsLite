package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * REST controller for booking operations.
 *
 * Blocker-13/14/15/16/17 (cr-java-0065): HttpSession replaced with Azure Cache for Redis
 * via Spring Session + RedisTemplate to enable stateless horizontal scaling.
 *
 * Blocker-20 (cr-java-0067): Static in-memory HashMap cache replaced with Azure Cache for
 * Redis (RedisTemplate) with TTL policies to prevent memory exhaustion and ensure
 * cache consistency across instances.
 *
 * Blocker-10 (cr-java-0071): Hard-coded inventory URL replaced with externalized config
 * loaded from Azure App Configuration via @Value.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * RedisTemplate replaces both the static in-memory bookingCache (cr-java-0067)
     * and the HttpSession-based state storage (cr-java-0065).
     * Spring Session auto-configures Redis as the session store when
     * spring-session-data-redis is on the classpath and @EnableRedisHttpSession is active.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Blocker-10 (cr-java-0071): Inventory service URL externalised to Azure App Configuration.
    // Value is injected from application.properties which reads from Azure App Configuration.
    @Value("${app.inventory.endpoint}")
    private String inventoryEndpoint;

    // TTL for booking cache entries in Redis (in minutes)
    private static final long BOOKING_CACHE_TTL_MINUTES = 60L;

    // Key prefix for session guest name stored in Redis
    private static final String SESSION_GUEST_KEY_PREFIX = "session:guest:";

    // Key prefix for booking cache entries stored in Redis
    private static final String BOOKING_CACHE_KEY_PREFIX = "cache:booking:";

    /**
     * Creates a new booking and stores state in Azure Cache for Redis.
     * Replaces HttpSession.setAttribute and static HashMap cache with Redis operations.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Blocker-14/15 (cr-java-0065): Store session state in Redis instead of HttpSession.
        // Each entry has a TTL to prevent unbounded memory growth (also fixes blocker-20).
        if (!sessionId.isEmpty()) {
            redisTemplate.opsForValue().set(
                    SESSION_GUEST_KEY_PREFIX + sessionId,
                    guestName,
                    BOOKING_CACHE_TTL_MINUTES,
                    TimeUnit.MINUTES);
        }

        // Blocker-20 (cr-java-0067): Store booking in Redis with TTL instead of static HashMap.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set(
                BOOKING_CACHE_KEY_PREFIX + bookingId,
                booking,
                BOOKING_CACHE_TTL_MINUTES,
                TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking, reading session state from Redis.
     * Blocker-16 (cr-java-0065): HttpSession.getAttribute replaced with Redis lookup.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "") String sessionId) {

        // Blocker-16 (cr-java-0065): Read guest name from Redis — visible to all instances.
        String lastGuest = null;
        if (!sessionId.isEmpty()) {
            Object cached = redisTemplate.opsForValue().get(SESSION_GUEST_KEY_PREFIX + sessionId);
            lastGuest = cached != null ? cached.toString() : null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using the externalized inventory service endpoint.
     * Blocker-10 (cr-java-0071): inventoryEndpoint is injected from Azure App Configuration.
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // inventoryEndpoint is loaded from Azure App Configuration — no hard-coded URL
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a download reference for a monthly report.
     * Report path is resolved via Azure Blob Storage — no local file system dependency.
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report is stored in Azure Blob Storage; path is a blob name, not a local file path
        String blobName = month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("blobName", blobName);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
