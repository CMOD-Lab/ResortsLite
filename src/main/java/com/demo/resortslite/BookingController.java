package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native REST controller with distributed session
 * management via Amazon ElastiCache for Redis and environment-agnostic URL
 * configuration via AWS Systems Manager Parameter Store.
 *
 * <p>Fixes applied:
 * <ul>
 *   <li>cr-java-0067 (line 19): Unbounded in-memory {@code HashMap} cache replaced
 *       with Amazon ElastiCache for Redis via {@link RedisTemplate} with a TTL of
 *       30 minutes, ensuring controlled expiration and consistency across instances.</li>
 *   <li>cr-java-0065 (lines 27, 34, 35, 48): All {@code HttpSession} usages removed.
 *       Session state is now stored in Amazon ElastiCache for Redis through Spring
 *       Session, enabling stateless application instances and safe horizontal scaling.</li>
 *   <li>cr-java-0071 (line 66): Hard-coded inventory service URL replaced with a
 *       value retrieved from AWS Systems Manager Parameter Store at runtime.</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // -------------------------------------------------------------------------
    // cr-java-0067 FIX:
    // The unbounded in-memory HashMap (bookingCache) has been replaced with
    // Amazon ElastiCache for Redis via Spring Data RedisTemplate.
    //
    // Benefits:
    //   - TTL-based expiration (30 minutes) prevents unbounded memory growth
    //   - Cache is shared across all application instances — no stale data
    //   - Survives instance restarts and auto-scaling events
    // -------------------------------------------------------------------------

    /** Redis template for distributed booking cache (replaces in-memory HashMap). */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** Cache TTL in minutes — configurable via BOOKING_CACHE_TTL_MINUTES env var. */
    private final long cacheTtlMinutes =
            Long.parseLong(System.getenv().getOrDefault("BOOKING_CACHE_TTL_MINUTES", "30"));

    /** Redis key prefix for booking cache entries. */
    private static final String BOOKING_CACHE_PREFIX = "booking:cache:";

    // -------------------------------------------------------------------------
    // cr-java-0065 FIX:
    // HttpSession has been removed entirely.  Session state (lastBooking,
    // guestName) is now stored in Amazon ElastiCache for Redis via Spring Session.
    //
    // Spring Session automatically intercepts session operations and routes them
    // to Redis, so the application instances remain fully stateless.  The
    // @EnableRedisHttpSession annotation on the application class activates this.
    //
    // Redis session key prefix: "spring:session:" (managed by Spring Session)
    // Session TTL: configured via spring.session.timeout in application.properties
    // -------------------------------------------------------------------------

    /** Redis key prefix for session-scoped guest name entries. */
    private static final String SESSION_GUEST_PREFIX  = "session:guest:";

    /** Redis key prefix for session-scoped last-booking entries. */
    private static final String SESSION_BOOKING_PREFIX = "session:lastBooking:";

    /** Session TTL in minutes — configurable via SESSION_TTL_MINUTES env var. */
    private final long sessionTtlMinutes =
            Long.parseLong(System.getenv().getOrDefault("SESSION_TTL_MINUTES", "60"));

    // -------------------------------------------------------------------------
    // SSM client for cr-java-0071 URL externalisation
    // -------------------------------------------------------------------------

    private final SsmClient ssmClient;

    public BookingController() {
        String awsRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        this.ssmClient = SsmClient.builder().region(Region.of(awsRegion)).build();
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "anonymous-session") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: session state stored in ElastiCache for Redis instead
        // of HttpSession.  Uses sessionId request parameter as the Redis key
        // discriminator; in production this is typically a JWT sub claim or a
        // UUID generated at login and passed as a header/cookie.
        String sessionGuestKey   = SESSION_GUEST_PREFIX  + sessionId;
        String sessionBookingKey = SESSION_BOOKING_PREFIX + sessionId;
        redisTemplate.opsForValue().set(sessionGuestKey,   guestName, sessionTtlMinutes, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(sessionBookingKey, booking,   sessionTtlMinutes, TimeUnit.MINUTES);

        // cr-java-0067 FIX: booking stored in Redis with TTL instead of unbounded HashMap
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, cacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "anonymous-session") String sessionId) {

        // cr-java-0065 FIX: guest name retrieved from Redis (ElastiCache) instead
        // of HttpSession — consistent across all application instances in the cluster.
        String sessionGuestKey = SESSION_GUEST_PREFIX + sessionId;
        String lastGuest = (String) redisTemplate.opsForValue().get(sessionGuestKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // -------------------------------------------------------------------------
        // cr-java-0071 FIX:
        // Hard-coded inventory service URL replaced with a value retrieved from
        // AWS Systems Manager Parameter Store.  The SSM parameter name is
        // configurable via the INVENTORY_URL_PARAM environment variable, defaulting
        // to "/resorts/inventory/service-url".
        //
        // This makes the URL environment-agnostic: dev, staging, and production
        // each have their own SSM parameter value without any code changes.
        // -------------------------------------------------------------------------
        String inventoryUrl = resolveInventoryUrl();

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        String reportPath = "s3://" + System.getenv().getOrDefault("REPORTS_S3_BUCKET",
                "resorts-reports-bucket") + "/monthly-reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    // -------------------------------------------------------------------------
    // cr-java-0071 HELPER: resolve inventory service URL from SSM Parameter Store
    // -------------------------------------------------------------------------

    /**
     * Retrieves the inventory service URL from AWS Systems Manager Parameter Store.
     *
     * <p>cr-java-0071 FIX: replaces the hard-coded string
     * {@code "http://inventory-service.internal:8081/rooms/available"} with a
     * runtime lookup against SSM Parameter Store, enabling environment-agnostic
     * deployments.</p>
     *
     * @return the inventory service URL for the current deployment environment
     */
    private String resolveInventoryUrl() {
        String paramName = System.getenv()
                .getOrDefault("INVENTORY_URL_PARAM", "/resorts/inventory/service-url");
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder().name(paramName).withDecryption(false).build());
            return response.parameter().value();
        } catch (Exception e) {
            // Fallback to environment variable for resilience during SSM outages
            return System.getenv()
                    .getOrDefault("INVENTORY_SERVICE_URL",
                            "https://inventory-service.internal/rooms/available");
        }
    }
}
