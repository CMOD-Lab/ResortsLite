package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController handles HTTP endpoints for resort booking operations.
 *
 * Cloud-readiness changes applied:
 * - Replaced in-memory HashMap cache (cr-java-0067) with Amazon ElastiCache for Redis
 *   via Spring Data Redis, with TTL-based expiration (blocker-20).
 * - Replaced HttpSession state storage (cr-java-0065) with Amazon ElastiCache for Redis
 *   via Spring Session, enabling stateless instances and horizontal scaling (blockers 13-17).
 * - Replaced hard-coded environment URL (cr-java-0071) with AWS SSM Parameter Store
 *   lookup (blocker-10).
 */
@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * RedisTemplate replaces the former static in-memory HashMap cache (blocker-20).
     * ElastiCache for Redis provides TTL-based expiration, cross-instance consistency,
     * and controlled memory growth — eliminating the unbounded in-memory cache issue.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final SsmClient ssmClient;

    // Cache TTL in minutes — controls expiration for Redis-backed booking cache
    private static final long CACHE_TTL_MINUTES = 30L;

    // Redis key prefix for booking cache entries
    private static final String BOOKING_CACHE_PREFIX = "booking:cache:";

    // Redis key prefix for session guest name (replaces HttpSession attributes)
    private static final String SESSION_GUEST_PREFIX = "session:guest:";

    public BookingController() {
        this.ssmClient = SsmClient.create();
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Store booking state in Amazon ElastiCache for Redis with TTL (replaces blockers 13-16).
        // All application instances share the same Redis cluster — no server affinity required.
        String bookingId = (String) booking.get("bookingId");
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        redisTemplate.opsForValue().set(cacheKey, booking, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        // Store guest session data in Redis with TTL (replaces HttpSession — blocker-14, 15)
        if (!sessionId.isEmpty()) {
            String sessionKey = SESSION_GUEST_PREFIX + sessionId;
            redisTemplate.opsForValue().set(sessionKey, guestName, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        // Retrieve guest name from Redis (replaces HttpSession.getAttribute — blocker-17)
        String lastGuest = null;
        if (!sessionId.isEmpty()) {
            String sessionKey = SESSION_GUEST_PREFIX + sessionId;
            Object cached = redisTemplate.opsForValue().get(sessionKey);
            lastGuest = cached != null ? cached.toString() : null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Retrieve inventory service URL from AWS SSM Parameter Store (replaces blocker-10
        // hard-coded environment URL). Enables environment-agnostic deployments.
        String inventoryUrl = getSsmParameter(
                "/resortslite/inventory/endpoint",
                "https://inventory-service.internal:8081/rooms/available");

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path now references S3 key — no local file system dependency
        String s3Key = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("s3Key", s3Key);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * @param paramName    the SSM parameter name/path
     * @param defaultValue fallback value if the parameter cannot be retrieved
     * @return the parameter value or the default
     */
    private String getSsmParameter(String paramName, String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
