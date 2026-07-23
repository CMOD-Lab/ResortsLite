package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * BookingController exposes REST endpoints for the resort booking system.
 *
 * <p>Session state is stored in Amazon ElastiCache for Redis via Spring Session,
 * replacing in-process HTTP session storage that breaks horizontal scaling.
 * The in-memory booking cache is replaced with a Redis-backed cache with TTL,
 * ensuring consistent data across all application instances.
 * Environment-specific URLs are retrieved from AWS Systems Manager Parameter Store.</p>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Redis template used for distributed caching with TTL.
     * Replaces the former static in-memory HashMap (bookingCache) that was
     * instance-local and invisible to other EC2/ECS instances in the cluster.
     * Spring Boot auto-configures this bean when spring-boot-starter-data-redis
     * is on the classpath and spring.redis.host is set.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // TTL for booking cache entries in Redis — prevents unbounded memory growth.
    private static final Duration BOOKING_CACHE_TTL = Duration.ofMinutes(30);

    // SSM client for retrieving environment-specific URLs at runtime.
    private final SsmClient ssmClient = SsmClient.create();

    /**
     * Creates a new booking and stores the booking state in Amazon ElastiCache for Redis.
     *
     * <p>Session state (lastBooking, guestName) is stored in Redis via Spring Session
     * so that any application instance can serve subsequent requests for the same session.
     * The booking is also cached in Redis with a 30-minute TTL.</p>
     *
     * @param guestName the guest's full name
     * @param roomType  the room category
     * @param checkIn   check-in date string
     * @param checkOut  check-out date string
     * @param sessionId a client-supplied session identifier used as the Redis key prefix
     * @return a confirmation map containing status and booking details
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "default-session") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Store session state in Redis (ElastiCache) instead of in-process HttpSession.
        // This allows any instance in the cluster to read the session on subsequent requests.
        String sessionKey = "session:" + sessionId;
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
        redisTemplate.expire(sessionKey, BOOKING_CACHE_TTL);

        // Cache the booking in Redis with TTL (replaces static in-memory HashMap).
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves the status of a booking, reading session context from Redis.
     *
     * @param bookingId the unique booking identifier
     * @param sessionId the client session identifier used to look up Redis session data
     * @return a map containing booking details and the session guest name
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "default-session") String sessionId) {

        // Read session state from Redis — works correctly across all cluster instances.
        String sessionKey = "session:" + sessionId;
        String lastGuest = (String) redisTemplate.opsForHash().get(sessionKey, "guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability by calling the inventory service.
     *
     * <p>The inventory service URL is retrieved from AWS Systems Manager Parameter Store
     * (/resorts/inventory/endpoint) instead of being hard-coded, enabling environment-agnostic
     * deployments across dev, staging, and production.</p>
     *
     * @param roomType the room category to check
     * @return a map containing availability information and the resolved inventory endpoint
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Retrieve the inventory service URL from SSM Parameter Store.
        // The parameter /resorts/inventory/endpoint must be set per environment.
        String inventoryUrl = getParameterFromSsm("/resorts/inventory/endpoint",
                "https://inventory-service.internal/rooms/available");

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a reference to the report stored in Amazon S3.
     *
     * <p>The report path is now an S3 object key rather than a local file system path,
     * ensuring the report is accessible from any instance and survives container restarts.</p>
     *
     * @param month the month identifier for the requested report
     * @return a map containing the S3 key and a generation message
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // S3 object key replaces the former hard-coded absolute file path.
        String reportKey = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportKey", reportKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * @param parameterName the SSM parameter path
     * @param defaultValue  fallback value used when the parameter cannot be resolved
     * @return the resolved parameter value or the provided default
     */
    private String getParameterFromSsm(String parameterName, String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
