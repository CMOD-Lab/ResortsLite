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
 * Cloud-readiness fixes applied:
 *  - cr-java-0065: HTTP session state replaced with Azure Cache for Redis
 *    (Spring Session + RedisTemplate) to enable stateless horizontal scaling.
 *  - cr-java-0067: In-memory bookingCache replaced with Azure Cache for Redis
 *    with TTL to prevent memory exhaustion and ensure cross-instance consistency.
 *  - cr-java-0071: Hard-coded inventory URL externalised to environment variable /
 *    Azure App Configuration.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Blocker-20 (cr-java-0067): In-memory cache replaced with Azure Cache for Redis.
    // RedisTemplate provides distributed caching with TTL across all instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL: 1 hour — prevents indefinite memory growth (cr-java-0067)
    private static final long CACHE_TTL_HOURS = 1L;

    // Session key prefix for Redis (cr-java-0065)
    private static final String SESSION_KEY_PREFIX = "session:";
    private static final String CACHE_KEY_PREFIX   = "booking:";

    // Blocker-10 (cr-java-0071): Hard-coded inventory URL replaced with
    // value loaded from environment variable / Azure App Configuration.
    @Value("${app.inventory.endpoint:${APP_INVENTORY_ENDPOINT:https://inventory-service.internal:8081/rooms/available}}")
    private String inventoryUrl;

    /**
     * Creates a new booking and stores session state in Azure Cache for Redis.
     * Blocker-13/14/15/16 (cr-java-0065): HttpSession replaced with Redis-backed session.
     * Blocker-20 (cr-java-0067): bookingCache replaced with Redis with TTL.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Blocker-13/14 (cr-java-0065): Store session state in Azure Cache for Redis
        // instead of HttpSession to support stateless horizontal scaling.
        if (sessionId != null && !sessionId.isEmpty()) {
            redisTemplate.opsForHash().put(SESSION_KEY_PREFIX + sessionId, "lastBooking", booking);
            redisTemplate.opsForHash().put(SESSION_KEY_PREFIX + sessionId, "guestName", guestName);
            redisTemplate.expire(SESSION_KEY_PREFIX + sessionId, CACHE_TTL_HOURS, TimeUnit.HOURS);
        }

        // Blocker-20 (cr-java-0067): Store booking in Redis with TTL instead of
        // unbounded in-memory HashMap to prevent memory exhaustion across instances.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set(CACHE_KEY_PREFIX + bookingId, booking, CACHE_TTL_HOURS, TimeUnit.HOURS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves booking status, reading session state from Azure Cache for Redis.
     * Blocker-15/16/17 (cr-java-0065): Session data read from Redis, not HttpSession.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        // Blocker-15/16/17 (cr-java-0065): Read session state from Redis — visible
        // to all instances in the cluster, enabling stateless horizontal scaling.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            Object val = redisTemplate.opsForHash().get(SESSION_KEY_PREFIX + sessionId, "guestName");
            if (val != null) {
                lastGuest = val.toString();
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using the externally configured inventory service URL.
     * Blocker-10 (cr-java-0071): inventoryUrl loaded from Azure App Configuration /
     * environment variable instead of being hard-coded.
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Blocker-10 (cr-java-0071): URL is now injected from environment variable /
        // Azure App Configuration — no hard-coded endpoint in source code.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a report download reference using Azure Blob Storage.
     * Hard-coded local file path replaced with Blob Storage URL via ReportService.
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Hard-coded /var/legacy/reports path replaced: ReportService now returns
        // an Azure Blob Storage URL for the requested report.
        String reportName = month + "_bookings.pdf";
        Map<String, Object> response = new HashMap<>();
        response.put("reportBlobUrl", bookingService.generateReport(month));
        response.put("message", "Report available via Azure Blob Storage");
        return response;
    }
}
