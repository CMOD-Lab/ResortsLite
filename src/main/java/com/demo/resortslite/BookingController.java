package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native implementation.
 *
 * <p>HTTP session state (javax.servlet.http.HttpSession) has been replaced with
 * Amazon ElastiCache for Redis via Spring Session, enabling stateless application
 * instances with centralised, distributed session management
 * (blocker-13 through blocker-17: cr-java-0065).
 *
 * <p>The unbounded in-memory {@code HashMap} cache has been replaced with
 * Amazon ElastiCache for Redis with a TTL policy to prevent indefinite memory
 * growth and ensure cache consistency across instances
 * (blocker-20: cr-java-0067).
 *
 * <p>The hard-coded inventory service URL has been replaced with a value
 * retrieved from AWS Systems Manager Parameter Store
 * (blocker-10: cr-java-0071).
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // -------------------------------------------------------------------------
    // blocker-20 (cr-java-0067) — In-Memory Caching Without TTL
    //
    // The static HashMap<String, Object> bookingCache has been replaced with
    // Amazon ElastiCache for Redis via Spring's RedisTemplate.  Entries are
    // stored with a TTL (default 30 minutes) to prevent unbounded growth and
    // ensure consistency across horizontally-scaled instances.
    // -------------------------------------------------------------------------
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final long CACHE_TTL_MINUTES = 30L;
    private static final String CACHE_KEY_PREFIX = "booking:";
    private static final String SESSION_KEY_PREFIX = "session:";

    // -------------------------------------------------------------------------
    // blocker-10 (cr-java-0071) — Hard-coded Environment URLs
    //
    // "http://inventory-service.internal:8081/rooms/available" replaced with a
    // value retrieved from AWS Systems Manager Parameter Store at runtime.
    // -------------------------------------------------------------------------
    private final String inventoryUrlParamName =
            System.getenv().getOrDefault(
                    "INVENTORY_URL_PARAM",
                    "/resorts/inventory/available-url");

    @Autowired
    private SsmClient ssmClient;

    /**
     * Resolves the inventory service URL from AWS Systems Manager Parameter Store.
     * Replaces the hard-coded {@code http://inventory-service.internal:8081/rooms/available}
     * URL (blocker-10: cr-java-0071).
     */
    private String resolveInventoryUrl() {
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(inventoryUrlParamName)
                            .withDecryption(false)
                            .build());
            return response.parameter().value();
        } catch (Exception e) {
            // Fallback to environment variable if SSM is unavailable
            return System.getenv().getOrDefault(
                    "INVENTORY_SERVICE_URL",
                    "https://inventory-service/rooms/available");
        }
    }

    /**
     * Creates a new booking.
     *
     * <p>blocker-13, blocker-14, blocker-15, blocker-16 (cr-java-0065):
     * Session state previously stored via {@code HttpSession.setAttribute()} is
     * now stored in Amazon ElastiCache for Redis using Spring Session, so all
     * application instances share the same session store.
     *
     * <p>blocker-20 (cr-java-0067): Booking cached in Redis with a 30-minute TTL
     * instead of an unbounded in-memory HashMap.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // blocker-13, blocker-14 (cr-java-0065): Store session state in Redis
        // (replaces session.setAttribute("lastBooking", booking) and
        //  session.setAttribute("guestName", guestName))
        if (sessionId != null && !sessionId.isEmpty()) {
            redisTemplate.opsForHash().put(SESSION_KEY_PREFIX + sessionId, "lastBooking", booking);
            redisTemplate.opsForHash().put(SESSION_KEY_PREFIX + sessionId, "guestName", guestName);
            redisTemplate.expire(SESSION_KEY_PREFIX + sessionId, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        }

        // blocker-20 (cr-java-0067): Cache booking in Redis with TTL
        // (replaces bookingCache.put(...) in unbounded HashMap)
        String cacheKey = CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking.
     *
     * <p>blocker-15, blocker-16, blocker-17 (cr-java-0065): Session attribute
     * previously read via {@code HttpSession.getAttribute("guestName")} is now
     * retrieved from Amazon ElastiCache for Redis, ensuring consistent reads
     * across all application instances.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        // blocker-17 (cr-java-0065): Read session state from Redis
        // (replaces session.getAttribute("guestName"))
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            Object sessionGuest = redisTemplate.opsForHash()
                    .get(SESSION_KEY_PREFIX + sessionId, "guestName");
            lastGuest = sessionGuest != null ? sessionGuest.toString() : null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability.
     *
     * <p>blocker-10 (cr-java-0071): The inventory service URL is resolved from
     * AWS Systems Manager Parameter Store instead of being hard-coded.
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // blocker-10 (cr-java-0071): URL resolved from SSM Parameter Store
        String inventoryUrl = resolveInventoryUrl();

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path now resolved via S3 (handled in ReportService)
        String reportKey = month + "_bookings.csv";

        Map<String, Object> response = new HashMap<>();
        response.put("reportKey", reportKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
