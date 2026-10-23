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
 * BookingController — cloud-native implementation.
 *
 * <p>HTTP session state (javax.servlet.http.HttpSession) has been replaced
 * with Amazon ElastiCache for Redis via Spring Session, enabling stateless
 * application instances with centralised, distributed session management
 * (blockers cr-java-0065). The unbounded in-memory HashMap cache has been
 * replaced with Amazon ElastiCache for Redis with TTL-based expiration
 * (blocker cr-java-0067). The hard-coded inventory service URL has been
 * externalised to AWS Systems Manager Parameter Store (blocker cr-java-0071).</p>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * RedisTemplate is provided by Spring Boot auto-configuration when
     * spring-boot-starter-data-redis is on the classpath and
     * spring.redis.host / spring.redis.port are set in application.properties.
     * It replaces the previous instance-local HashMap (bookingCache) with a
     * distributed, TTL-aware cache backed by Amazon ElastiCache for Redis.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL: 30 minutes — prevents unbounded memory growth and ensures
    // consistent data across all horizontally-scaled instances.
    private static final long CACHE_TTL_MINUTES = 30L;

    // Redis key prefixes for session data and booking cache entries.
    private static final String SESSION_KEY_PREFIX = "session:";
    private static final String CACHE_KEY_PREFIX   = "bookingCache:";

    // AWS region for SSM client.
    private static final String AWS_REGION =
            System.getenv().getOrDefault("AWS_REGION", "us-east-1");

    // SSM parameter name for the inventory service URL.
    private static final String SSM_INVENTORY_URL_PARAM =
            System.getenv().getOrDefault("SSM_INVENTORY_URL_PARAM", "/resortslite/inventory/service-url");

    private SsmClient ssmClient;

    private SsmClient getSsmClient() {
        if (ssmClient == null) {
            ssmClient = SsmClient.builder()
                    .region(Region.of(AWS_REGION))
                    .build();
        }
        return ssmClient;
    }

    /**
     * Retrieves the inventory service URL from AWS Systems Manager Parameter Store.
     *
     * <p>Replaces the previous hard-coded value
     * {@code http://inventory-service.internal:8081/rooms/available}
     * (blocker cr-java-0071).</p>
     *
     * @return inventory service URL string
     */
    private String getInventoryServiceUrl() {
        try {
            GetParameterResponse response = getSsmClient().getParameter(
                    GetParameterRequest.builder()
                            .name(SSM_INVENTORY_URL_PARAM)
                            .withDecryption(false)
                            .build());
            return response.parameter().value();
        } catch (Exception e) {
            return System.getenv().getOrDefault(
                    "INVENTORY_SERVICE_URL", "https://inventory-service.internal/rooms/available");
        }
    }

    /**
     * Creates a new booking and stores session state in Amazon ElastiCache for Redis.
     *
     * <p>Replaces {@code HttpSession.setAttribute()} calls with Redis-backed
     * distributed session storage so that any instance in the cluster can serve
     * subsequent requests for the same session (blockers cr-java-0065).
     * The booking is also cached in Redis with a TTL to replace the unbounded
     * in-memory HashMap (blocker cr-java-0067).</p>
     *
     * @param guestName guest full name
     * @param roomType  room category
     * @param checkIn   check-in date string
     * @param checkOut  check-out date string
     * @param sessionId session identifier passed by the client (e.g. from a cookie)
     * @return confirmation response map
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Store session state in Redis (ElastiCache) with TTL — replaces HttpSession.
        // This allows any horizontally-scaled instance to read the session data.
        if (!sessionId.isEmpty()) {
            redisTemplate.opsForHash().put(SESSION_KEY_PREFIX + sessionId, "lastBooking", booking);
            redisTemplate.opsForHash().put(SESSION_KEY_PREFIX + sessionId, "guestName", guestName);
            redisTemplate.expire(SESSION_KEY_PREFIX + sessionId, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        }

        // Cache booking in Redis with TTL — replaces the unbounded in-memory HashMap.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set(
                CACHE_KEY_PREFIX + bookingId, booking, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking, reading session context from Redis.
     *
     * <p>Replaces {@code HttpSession.getAttribute()} with a Redis lookup so
     * that the session data is available regardless of which instance handles
     * the request (blocker cr-java-0065).</p>
     *
     * @param bookingId the booking identifier
     * @param sessionId session identifier passed by the client
     * @return booking status map
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        // Read session state from Redis — visible to all instances in the cluster.
        String lastGuest = null;
        if (!sessionId.isEmpty()) {
            Object guestObj = redisTemplate.opsForHash().get(SESSION_KEY_PREFIX + sessionId, "guestName");
            if (guestObj != null) {
                lastGuest = guestObj.toString();
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using the inventory service URL retrieved from
     * AWS Systems Manager Parameter Store.
     *
     * <p>Replaces the hard-coded {@code http://inventory-service.internal:8081/rooms/available}
     * URL (blocker cr-java-0071).</p>
     *
     * @param roomType room category to check
     * @return availability response map
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        String inventoryUrl = getInventoryServiceUrl();

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a report download reference using S3-based storage.
     *
     * <p>The report is stored in Amazon S3 rather than a local file path.</p>
     *
     * @param month month identifier string
     * @return report reference map
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
