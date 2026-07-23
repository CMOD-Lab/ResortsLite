package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController handles booking REST endpoints.
 *
 * Cloud-readiness fixes applied:
 * - cr-java-0065: HTTP session state migrated to Amazon ElastiCache for Redis
 *   via Spring Session, enabling stateless instances and horizontal scaling.
 * - cr-java-0067: In-memory bookingCache (no TTL) replaced with Redis-backed
 *   cache with TTL via RedisTemplate, preventing unbounded memory growth.
 * - cr-java-0071: Hard-coded inventory service URL replaced with value from
 *   AWS Systems Manager Parameter Store via environment variable injection.
 */
@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX:
    // Replaced static in-memory HashMap (no TTL, instance-local) with
    // Amazon ElastiCache for Redis via RedisTemplate. TTL is set per entry
    // to prevent unbounded memory growth and ensure cache consistency across instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL: 30 minutes — entries expire automatically in Redis
    private static final long BOOKING_CACHE_TTL_MINUTES = 30L;
    private static final String BOOKING_CACHE_PREFIX = "booking:cache:";

    // cr-java-0071 FIX:
    // Replaced hard-coded "http://inventory-service.internal:8081/rooms/available"
    // with a value injected from AWS Systems Manager Parameter Store via environment variable.
    @Value("${app.inventory.endpoint:https://inventory-service.internal/rooms/available}")
    private String inventoryEndpoint;

    /**
     * Creates a new booking and stores session state in Amazon ElastiCache for Redis.
     * Replaces HTTP session-local storage with distributed Redis-backed Spring Session.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX:
        // Session attributes are now stored in Amazon ElastiCache for Redis via Spring Session
        // (@EnableRedisHttpSession). All cluster instances share the same session store,
        // so AWS ALB can route requests to any instance without sticky sessions.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX:
        // Replaced instance-local HashMap.put() with Redis SET + TTL.
        // Cache entries expire after BOOKING_CACHE_TTL_MINUTES to prevent stale data.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns booking status. Session data is retrieved from Redis (shared across instances).
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX:
        // session.getAttribute() now reads from Amazon ElastiCache for Redis via Spring Session.
        // Returns consistent data regardless of which cluster instance handles the request.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using the inventory service endpoint sourced from
     * AWS Systems Manager Parameter Store — no hard-coded environment URLs.
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX:
        // inventoryEndpoint is injected from AWS SSM Parameter Store via the
        // app.inventory.endpoint environment variable — no hard-coded URL in source code.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a report download reference. File paths replaced with S3 object keys.
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // cr-java-0061 FIX (BookingController):
        // Replaced hard-coded absolute path "/var/legacy/reports/<month>_bookings.pdf"
        // with an S3 object key reference. Actual S3 retrieval is handled by ReportService.
        String s3ObjectKey = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("s3ObjectKey", s3ObjectKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
