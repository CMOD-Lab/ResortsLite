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
 * BookingController — cloud-native implementation.
 *
 * <p><strong>Session state (blockers 13–17):</strong> HTTP session attributes
 * are now backed by Amazon ElastiCache for Redis via Spring Session
 * ({@code @EnableRedisHttpSession}). Any ECS/EKS instance can serve any
 * request without sticky sessions because session data is stored in the
 * shared Redis cluster rather than in-process JVM memory.</p>
 *
 * <p><strong>In-memory cache (blocker 20):</strong> The unbounded
 * {@code HashMap}-based {@code bookingCache} has been replaced with a
 * {@link RedisTemplate}-backed cache with a configurable TTL (default 30 min).
 * This prevents indefinite memory growth and ensures cache consistency across
 * all application instances.</p>
 *
 * <p><strong>Hard-coded inventory URL (blocker 10):</strong> The hard-coded
 * {@code http://inventory-service.internal:8081/rooms/available} URL has been
 * replaced with a value injected from application properties / environment
 * variables that are resolved from AWS Systems Manager Parameter Store.</p>
 */
@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Redis template used as a distributed cache with TTL.
     * Replaces the unbounded in-memory {@code HashMap} (blocker 20).
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Inventory service endpoint resolved from application.properties /
     * AWS SSM Parameter Store at runtime.
     * Replaces hard-coded "http://inventory-service.internal:8081/rooms/available"
     * (blocker 10).
     */
    @Value("${aws.ssm.param.inventory-endpoint}")
    private String inventoryEndpointParam;

    /** TTL (in minutes) for booking entries stored in the Redis cache. */
    private static final long BOOKING_CACHE_TTL_MINUTES = 30;

    /** Redis key prefix for cached booking objects. */
    private static final String BOOKING_CACHE_PREFIX = "booking:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Session attributes are now stored in Amazon ElastiCache for Redis via
        // Spring Session — visible to all instances in the cluster (blockers 13–17).
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // Cache booking in Redis with TTL — replaces unbounded in-memory HashMap (blocker 20).
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Session attribute read — backed by Redis, consistent across all instances (blocker 17).
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Inventory URL is resolved from AWS SSM Parameter Store via application.properties
        // — no hard-coded endpoint in source code (blocker 10).
        String inventoryUrl = inventoryEndpointParam;

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now an S3 object key — no absolute local file path dependency.
        String s3ReportKey = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("s3Key", s3ReportKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
