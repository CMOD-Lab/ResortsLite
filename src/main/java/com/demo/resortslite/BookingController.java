package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — REST endpoints for the ResortsLite booking service.
 *
 * <p>Cloud-readiness fixes applied:
 * <ul>
 *   <li>Blocker-13/14/15/16/17 (cr-java-0065): {@link javax.servlet.http.HttpSession}
 *       removed; session state is now stored in Google Cloud Memorystore for Redis via
 *       {@link RedisTemplate}, enabling stateless horizontal scaling.</li>
 *   <li>Blocker-20 (cr-java-0067): The unbounded in-memory {@code HashMap} cache is
 *       replaced with Redis-backed storage that includes a TTL, preventing memory
 *       exhaustion and ensuring cache consistency across instances.</li>
 *   <li>Blocker-10 (cr-java-0071): The hard-coded inventory service URL is externalised
 *       to the {@code app.inventory.endpoint} environment variable.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // -----------------------------------------------------------------------
    // Blocker-20 (cr-java-0067): Unbounded in-memory HashMap replaced with
    // Redis-backed cache (Memorystore for Redis) that supports TTL and is
    // shared across all application instances.
    // -----------------------------------------------------------------------
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** TTL for cached booking entries — 30 minutes. */
    private static final long BOOKING_CACHE_TTL_MINUTES = 30L;

    /** TTL for session attributes — 60 minutes. */
    private static final long SESSION_TTL_MINUTES = 60L;

    // -----------------------------------------------------------------------
    // Blocker-10 (cr-java-0071): Hard-coded inventory URL externalised to
    // environment variable via application.properties.
    // -----------------------------------------------------------------------
    @Value("${app.inventory.endpoint:https://inventory-svc.internal/rooms}")
    private String inventoryEndpoint;

    /**
     * Creates a new booking and stores session state in Redis.
     *
     * <p>Blocker-13/14 (cr-java-0065): {@code session.setAttribute("lastBooking")}
     * and {@code session.setAttribute("guestName")} replaced with Redis writes.
     * Blocker-20 (cr-java-0067): booking cached in Redis with TTL.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "anonymous") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Blocker-13/14 (cr-java-0065): Store session state in Redis (Memorystore)
        // instead of HttpSession so any instance can read it.
        String sessionKey = "session:" + sessionId;
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
        redisTemplate.expire(sessionKey, SESSION_TTL_MINUTES, TimeUnit.MINUTES);

        // Blocker-20 (cr-java-0067): Cache booking in Redis with TTL.
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking, reading session context from Redis.
     *
     * <p>Blocker-15/16/17 (cr-java-0065): {@code session.getAttribute("guestName")}
     * replaced with a Redis lookup so the value is available on any instance.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "anonymous") String sessionId) {

        // Blocker-15 (cr-java-0065): Read session attribute from Redis.
        String sessionKey = "session:" + sessionId;
        Object lastGuest = redisTemplate.opsForHash().get(sessionKey, "guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability.
     *
     * <p>Blocker-10 (cr-java-0071): Inventory service URL is now injected from
     * {@code app.inventory.endpoint} environment variable.
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Blocker-10 (cr-java-0071): URL sourced from externalised configuration.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a download reference for a monthly report stored in GCS.
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
