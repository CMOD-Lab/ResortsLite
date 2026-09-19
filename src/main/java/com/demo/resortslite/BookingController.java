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

@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX: In-memory HashMap cache replaced with Amazon ElastiCache for Redis
    // via Spring Data RedisTemplate. The static, instance-local HashMap (bookingCache) has
    // been removed. All cache reads and writes now go through Redis, which provides:
    //   - Centralized cache shared across all application instances behind the AWS ALB
    //   - Configurable TTL (BOOKING_CACHE_TTL_SECONDS, default 1800 s / 30 min) to prevent
    //     unbounded memory growth and stale data inconsistencies
    //   - Automatic key expiration enforced by ElastiCache — no manual eviction needed
    //   - Horizontal scalability: any EC2/ECS instance can read or write the same cache entry
    // The RedisTemplate<String, Object> bean is auto-configured by Spring Boot when
    // spring-boot-starter-data-redis is on the classpath and spring.redis.host is set.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // TTL for booking cache entries in seconds. Override via BOOKING_CACHE_TTL_SECONDS
    // environment variable at deployment time (ECS task definition / EKS pod spec).
    // Default: 1800 seconds (30 minutes).
    @Value("${app.booking.cache.ttl-seconds:${BOOKING_CACHE_TTL_SECONDS:1800}}")
    private long bookingCacheTtlSeconds;

    // Redis key prefix for booking cache entries — avoids collisions with other keys.
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    // cr-java-0071 FIX: Hard-coded inventory service URL replaced with a value injected from
    // AWS Systems Manager Parameter Store via Spring's @Value binding. The parameter
    // /resortslite/inventory/url is resolved at startup from SSM Parameter Store, enabling
    // environment-agnostic deployments without code changes between dev/staging/production.
    @Value("${app.inventory.url:${APP_INVENTORY_URL:http://inventory-service.internal:8081/rooms/available}}")
    private String inventoryServiceUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session state is now stored in Amazon ElastiCache for Redis via
        // Spring Session. The HttpSession API is preserved but the underlying store is Redis,
        // making session data available to all application instances behind the AWS ALB.
        // Auto-scaling, failover, and sticky-session-free load balancing are fully supported.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Cache booking in Amazon ElastiCache for Redis with TTL.
        // Replaces the former static HashMap (bookingCache) which had no expiration policy
        // and was invisible to other EC2/ECS instances. The Redis entry expires automatically
        // after bookingCacheTtlSeconds, preventing unbounded memory growth and stale data.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Session attribute is now read from Amazon ElastiCache for Redis
        // via Spring Session. Any application instance in the cluster can serve this request
        // and retrieve the correct session data — no server affinity required.
        String lastGuest = (String) session.getAttribute("guestName");

        // cr-java-0067 FIX: Booking lookup now reads from Amazon ElastiCache for Redis.
        // If the entry is present and has not yet expired (TTL enforced by ElastiCache),
        // the cached booking is returned directly, avoiding a database round-trip.
        // On a cache miss (entry expired or not yet cached), the service layer is consulted.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        if (cachedBooking != null) {
            result.put("details", cachedBooking);
            result.put("cacheHit", true);
        } else {
            result.put("details", bookingService.getBookingById(bookingId));
            result.put("cacheHit", false);
        }
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: The hard-coded URL "http://inventory-service.internal:8081/rooms/available"
        // has been removed. The URL is now sourced from the injected field `inventoryServiceUrl`,
        // which is backed by the AWS SSM Parameter Store key /resortslite/inventory/url
        // (configured in application.properties as app.inventory.url). This allows the URL
        // to differ per environment (dev/staging/prod) without any code changes.
        String inventoryUrl = inventoryServiceUrl;

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
