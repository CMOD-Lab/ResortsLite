package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native, stateless REST controller.
 *
 * cr-java-0065 fix: All HTTP session state (lastBooking, guestName) has been
 * migrated to Amazon ElastiCache for Redis via Spring Session / RedisTemplate.
 * Session attributes are stored in Redis with a configurable TTL so that any
 * EC2 instance behind the AWS ALB can read the same session data, enabling
 * true horizontal scaling and zero-downtime auto-scaling / failover.
 *
 * cr-java-0067 fix: The unbounded in-memory bookingCache (HashMap without TTL)
 * has been removed and replaced with Amazon ElastiCache for Redis via
 * RedisTemplate. Each cache entry is stored under a namespaced key with a
 * configurable TTL (BOOKING_CACHE_TTL_MINUTES = 60 minutes), preventing
 * indefinite memory growth, stale data, and cross-instance inconsistency.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0065 / cr-java-0067 fix: RedisTemplate replaces both HttpSession
     * and the local in-memory HashMap for distributed, TTL-backed storage backed
     * by Amazon ElastiCache for Redis.
     * Spring Boot auto-configures this bean when spring-boot-starter-data-redis
     * and spring-session-data-redis are on the classpath and
     * spring.redis.host / spring.redis.port are set in application.properties.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0065 fix: session TTL — 30 minutes, matching typical HTTP session timeout.
    private static final long SESSION_TTL_MINUTES = 30;

    /**
     * cr-java-0067 fix: Booking cache TTL — 60 minutes.
     * Replaces the former unbounded static HashMap (bookingCache) that had no
     * expiration policy and was invisible to other EC2 instances. Entries stored
     * in ElastiCache for Redis under the "booking:cache:" namespace are
     * automatically evicted after this TTL, preventing memory exhaustion and
     * ensuring all application instances share a consistent, up-to-date view of
     * cached booking data.
     */
    private static final long BOOKING_CACHE_TTL_MINUTES = 60;

    // cr-java-0067 fix: Redis key namespace for the booking cache.
    private static final String BOOKING_CACHE_PREFIX = "booking:cache:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 fix: Store booking state in Amazon ElastiCache for Redis instead of
        // HttpSession. The Redis key is scoped to the caller-supplied sessionId so that
        // any application instance can retrieve the same data. A TTL is applied to
        // prevent stale entries from accumulating in the cache.
        String lastBookingKey = "session:" + sessionId + ":lastBooking";
        String guestNameKey   = "session:" + sessionId + ":guestName";
        redisTemplate.opsForValue().set(lastBookingKey, booking,    SESSION_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(guestNameKey,   guestName,  SESSION_TTL_MINUTES, TimeUnit.MINUTES);

        // cr-java-0067 fix: Cache the booking in Amazon ElastiCache for Redis with a TTL
        // instead of the former unbounded static HashMap. This ensures:
        //   1. Controlled expiration — entries are evicted after BOOKING_CACHE_TTL_MINUTES,
        //      preventing indefinite memory growth and out-of-memory errors.
        //   2. Cross-instance consistency — all EC2 instances behind the AWS ALB share the
        //      same Redis-backed cache, eliminating stale-data inconsistencies.
        //   3. Centralised cache management — ElastiCache for Redis provides monitoring,
        //      eviction policies, and cluster-level visibility via AWS CloudWatch.
        String bookingCacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(bookingCacheKey, booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        // cr-java-0065 fix: Retrieve guest name from Amazon ElastiCache for Redis instead of
        // HttpSession. Because Redis is a shared, external store, this read succeeds
        // regardless of which EC2 instance handles the request.
        String guestNameKey = "session:" + sessionId + ":guestName";
        String lastGuest = (String) redisTemplate.opsForValue().get(guestNameKey);

        // cr-java-0067 fix: Look up the cached booking from ElastiCache for Redis instead
        // of the former instance-local HashMap. Returns null (cache miss) if the TTL has
        // expired or the entry was never cached, in which case the caller falls back to
        // bookingService.getBookingById() for a fresh database read.
        String bookingCacheKey = BOOKING_CACHE_PREFIX + bookingId;
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedBooking = (Map<String, Object>) redisTemplate.opsForValue().get(bookingCacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP call to
        // internal inventory service. AWS ALB, WAF, and Well-Architected security review
        // enforce HTTPS. This call will be blocked or flagged in a cloud-native setup.
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available"; // cr-java-0088

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path. This path does not exist inside a container image. Container images
        // have their own isolated file systems — /var/legacy/reports won't be present.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
