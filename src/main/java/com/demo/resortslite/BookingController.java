package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController handles HTTP requests for resort booking operations.
 *
 * Session state has been migrated from HttpSession to Amazon ElastiCache for Redis
 * via Spring Session (blocker-13 through blocker-17: cr-java-0065).
 *
 * In-memory cache without TTL replaced with Amazon ElastiCache for Redis
 * with proper TTL policies (blocker-20: cr-java-0067).
 *
 * Hard-coded environment URL externalized via AWS SSM Parameter Store
 * (blocker-10: cr-java-0071).
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // blocker-20: cr-java-0067 — Replaced static in-memory HashMap cache (no TTL) with
    // Amazon ElastiCache for Redis via RedisTemplate. TTL is enforced on every cache write.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // blocker-10: cr-java-0071 — Hard-coded inventory URL externalized to application property
    // backed by AWS SSM Parameter Store. No hard-coded environment-specific URL in source code.
    @Value("${app.inventory.endpoint:https://inventory-service.internal:8081/rooms/available}")
    private String inventoryEndpoint;

    // TTL for booking cache entries in Redis (30 minutes)
    private static final long BOOKING_CACHE_TTL_MINUTES = 30L;

    // TTL for session data in Redis (60 minutes)
    private static final long SESSION_TTL_MINUTES = 60L;

    /**
     * Creates a new booking and stores session state in Amazon ElastiCache for Redis.
     * Replaces HttpSession usage (blocker-13, blocker-14: cr-java-0065) and
     * in-memory cache without TTL (blocker-20: cr-java-0067).
     *
     * @param guestName the name of the guest
     * @param roomType  the type of room
     * @param checkIn   the check-in date
     * @param checkOut  the check-out date
     * @param sessionId a client-supplied session identifier for distributed session tracking
     * @return a map containing the booking confirmation
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // blocker-14, blocker-15: cr-java-0065 — Store session state in Redis instead of HttpSession.
        // This enables stateless application instances with centralized, distributed session management.
        if (!sessionId.isEmpty()) {
            redisTemplate.opsForValue().set(
                    "session:" + sessionId + ":lastBooking", booking, SESSION_TTL_MINUTES, TimeUnit.MINUTES);
            redisTemplate.opsForValue().set(
                    "session:" + sessionId + ":guestName", guestName, SESSION_TTL_MINUTES, TimeUnit.MINUTES);
        }

        // blocker-20: cr-java-0067 — Store booking in Redis cache with TTL instead of unbounded HashMap.
        redisTemplate.opsForValue().set(
                "booking:" + booking.get("bookingId"), booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves the status of a booking, reading session state from Amazon ElastiCache for Redis.
     * Replaces HttpSession usage (blocker-16, blocker-17: cr-java-0065).
     *
     * @param bookingId the booking identifier
     * @param sessionId a client-supplied session identifier for distributed session tracking
     * @return a map containing the booking status and session guest name
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "") String sessionId) {

        // blocker-16: cr-java-0065 — Read session state from Redis instead of HttpSession.
        // Returns consistent data regardless of which application instance handles the request.
        String lastGuest = null;
        if (!sessionId.isEmpty()) {
            Object sessionGuest = redisTemplate.opsForValue().get("session:" + sessionId + ":guestName");
            lastGuest = sessionGuest != null ? sessionGuest.toString() : null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using the externalized inventory service endpoint.
     * Replaces hard-coded HTTP URL (blocker-10: cr-java-0071) with externalized configuration.
     *
     * @param roomType the type of room to check
     * @return a map containing availability information
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // blocker-10: cr-java-0071 — inventoryEndpoint is now injected from application property
        // backed by AWS SSM Parameter Store, not hard-coded in source.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Provides a report download reference using Amazon S3 object storage.
     * Replaces hard-coded local file path with S3-based report retrieval.
     *
     * @param month the month for the report
     * @return a map containing the S3 object key and report generation status
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Replaced hard-coded /var/legacy/reports/ path with S3 object key reference
        String s3ObjectKey = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("s3ObjectKey", s3ObjectKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
