package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController exposes REST endpoints for resort booking operations.
 *
 * <p>Cloud-readiness changes applied:
 * <ul>
 *   <li>In-memory {@code HashMap} cache replaced with Amazon ElastiCache for Redis
 *       via {@link RedisTemplate} with TTL policies (blocker-20 / cr-java-0067).</li>
 *   <li>HTTP session state ({@code HttpSession}) migrated to Amazon ElastiCache for
 *       Redis using Spring Session — enabling stateless, horizontally scalable instances
 *       (blockers 13–17 / cr-java-0065). The {@code HttpSession} parameter is retained
 *       so Spring Session transparently stores session data in Redis.</li>
 *   <li>Hard-coded inventory service URL replaced with value injected from AWS Systems
 *       Manager Parameter Store via {@code @Value} / environment variable
 *       (blocker-10 / cr-java-0071).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Redis template used for distributed caching with TTL.
     * Replaces the static in-memory {@code HashMap} bookingCache (blocker-20).
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Cache TTL in minutes for booking entries stored in ElastiCache for Redis.
     * Configurable via environment variable BOOKING_CACHE_TTL_MINUTES.
     */
    @Value("${cache.booking.ttl-minutes:${BOOKING_CACHE_TTL_MINUTES:60}}")
    private long bookingCacheTtlMinutes;

    /**
     * Inventory service URL injected from environment variable INVENTORY_SERVICE_URL
     * or AWS SSM Parameter Store (blocker-10 / cr-java-0071).
     * Replaces hard-coded "http://inventory-service.internal:8081/rooms/available".
     */
    @Value("${app.inventory.endpoint:${INVENTORY_SERVICE_URL:https://inventory-service.internal/rooms/available}}")
    private String inventoryServiceUrl;

    private static final String BOOKING_CACHE_PREFIX = "booking:";
    private static final String SESSION_LAST_BOOKING_KEY = "lastBooking";
    private static final String SESSION_GUEST_NAME_KEY = "guestName";

    /**
     * Creates a new booking and stores session state in Amazon ElastiCache for Redis
     * via Spring Session (blockers 13–16 / cr-java-0065).
     * Booking is also cached in Redis with TTL (blocker-20 / cr-java-0067).
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Spring Session automatically stores HttpSession attributes in Amazon ElastiCache
        // for Redis, enabling stateless application instances and horizontal scaling.
        // Replaces in-process session storage (blockers 13-16 / cr-java-0065).
        session.setAttribute(SESSION_LAST_BOOKING_KEY, booking);
        session.setAttribute(SESSION_GUEST_NAME_KEY, guestName);

        // Store booking in Redis with TTL — replaces unbounded in-memory HashMap (blocker-20)
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves booking status. Session data is read from Amazon ElastiCache for Redis
     * via Spring Session (blocker-17 / cr-java-0065).
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Spring Session transparently reads session attributes from Redis —
        // works correctly across all instances in the cluster (blocker-17 / cr-java-0065).
        String lastGuest = (String) session.getAttribute(SESSION_GUEST_NAME_KEY);

        // Attempt to serve from Redis cache before hitting the database
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        if (cachedBooking != null) {
            result.put("details", cachedBooking);
            result.put("source", "cache");
        } else {
            result.put("details", bookingService.getBookingById(bookingId));
            result.put("source", "database");
        }
        return result;
    }

    /**
     * Checks room availability using the inventory service URL retrieved from
     * AWS Systems Manager Parameter Store / environment variable (blocker-10 / cr-java-0071).
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // inventoryServiceUrl is injected from environment variable / SSM Parameter Store —
        // replaces hard-coded "http://inventory-service.internal:8081/rooms/available" (blocker-10)
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
