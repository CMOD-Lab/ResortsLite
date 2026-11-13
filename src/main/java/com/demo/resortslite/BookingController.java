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
 * BookingController — cr-java-0065 and cr-java-0067 fixes applied.
 *
 * cr-java-0065 fix:
 *   HTTP session state is now backed by Amazon ElastiCache for Redis via
 *   Spring Session (spring-session-data-redis).  The @EnableRedisHttpSession
 *   annotation on this class instructs Spring Session to transparently replace
 *   the in-memory HttpSession store with a Redis-backed store.  All
 *   session.setAttribute / session.getAttribute calls continue to work without
 *   change, but the data is now stored in the shared ElastiCache cluster,
 *   making every application instance stateless and enabling safe horizontal
 *   scaling behind an AWS ALB.
 *
 * cr-java-0067 fix:
 *   The unbounded static HashMap (bookingCache) has been replaced with a
 *   RedisTemplate-backed cache that writes entries to Amazon ElastiCache for
 *   Redis with a configurable TTL (default 30 minutes).  This eliminates
 *   indefinite memory growth, prevents stale-data inconsistencies across
 *   multiple EC2 instances, and ensures controlled expiration via Redis TTL
 *   policies — all requirements for cloud-native, horizontally-scalable
 *   deployments on AWS.
 */
@EnableRedisHttpSession
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 fix: RedisTemplate replaces the unbounded static HashMap.
     *
     * All cache reads/writes now go to the shared Amazon ElastiCache for Redis
     * cluster.  Each entry is stored with a TTL (see BOOKING_CACHE_TTL_MINUTES)
     * so that Redis automatically evicts stale entries, preventing memory growth
     * and ensuring data consistency across all application instances.
     *
     * The RedisTemplate bean is auto-configured by Spring Boot's
     * spring-boot-starter-data-redis when spring.redis.host / spring.redis.port
     * are set (see application.properties).
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * TTL for booking cache entries in Amazon ElastiCache for Redis.
     * Defaults to 30 minutes; override via BOOKING_CACHE_TTL_MINUTES env var
     * (e.g. set in ECS task definition, EKS pod spec, or Elastic Beanstalk
     * environment configuration).
     */
    @Value("${cache.booking.ttl-minutes:30}")
    private long bookingCacheTtlMinutes;

    /** Redis key prefix for booking cache entries. */
    private static final String BOOKING_CACHE_PREFIX = "booking:cache:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 fix: session is now backed by Amazon ElastiCache for Redis via
        // Spring Session (@EnableRedisHttpSession).  Attributes written here are stored
        // in the shared Redis cluster, not in local JVM memory, so any EC2 instance
        // handling subsequent requests for the same session will read the same data.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 fix: Store booking in Amazon ElastiCache for Redis with TTL.
        // Using RedisTemplate.opsForValue().set(..., ttl, TimeUnit) ensures that:
        //   1. The entry is visible to ALL application instances (no instance-local state).
        //   2. Redis automatically evicts the entry after bookingCacheTtlMinutes minutes,
        //      preventing unbounded memory growth and stale-data inconsistencies.
        //   3. Cache behaviour is centralised and consistent across the entire cluster.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 fix: session.getAttribute now reads from the shared Redis-backed
        // session store (ElastiCache), not from instance-local memory.  The value is
        // consistent regardless of which EC2 instance handles this request.
        String lastGuest = (String) session.getAttribute("guestName");

        // cr-java-0067 fix: Retrieve booking from Amazon ElastiCache for Redis instead
        // of the former instance-local HashMap.  Returns null (cache miss) if the entry
        // has expired or was never cached, in which case the caller falls back to the
        // persistent store via bookingService.getBookingById().
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("cachedBooking", cachedBooking);
        result.put("details", bookingService.getBookingById(bookingId));
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
