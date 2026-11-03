package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native REST controller.
 *
 * cr-java-0065 FIX: All HTTP session state has been migrated from the in-process
 * {@code javax.servlet.http.HttpSession} to Amazon ElastiCache for Redis via
 * Spring Session.  The {@code HttpSession} parameter has been removed from every
 * handler method; session reads/writes now go through the injected
 * {@link SessionRepository}, which delegates to the Redis-backed Spring Session
 * store configured in {@link RedisSessionConfig}.
 *
 * cr-java-0067 FIX: The unbounded in-memory {@code HashMap} cache (bookingCache)
 * has been replaced with a Redis-backed cache using {@link RedisTemplate} connected
 * to Amazon ElastiCache for Redis.  Each cache entry is stored with a configurable
 * TTL (default 3600 seconds / 1 hour) so entries expire automatically, preventing
 * indefinite memory growth and stale data.  Because the cache lives in the shared
 * ElastiCache cluster, all application instances see the same data — eliminating
 * the cache-miss inconsistencies that occurred with the previous instance-local map.
 *
 * Benefits for AWS deployments:
 *  - Sessions survive instance termination and auto-scaling events.
 *  - AWS ALB can distribute requests to any instance without sticky sessions.
 *  - Session data is centralised in ElastiCache — no instance-local state.
 *  - Booking cache entries expire via TTL — no unbounded memory growth.
 *  - Cache is shared across all instances — consistent reads after writes.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0071 FIX: Inventory service URL is now externalised via AWS Systems Manager
    // Parameter Store. The parameter name is resolved from the environment variable
    // INVENTORY_SERVICE_URL_PARAM (defaulting to /resortslite/inventory/service-url).
    // At runtime the AwsSsmConfig bean resolves the actual URL from SSM Parameter Store,
    // making the application environment-agnostic and deployable across dev/staging/prod
    // without any code changes.
    @Autowired
    private AwsSsmConfig awsSsmConfig;

    // cr-java-0065 FIX: Spring Session SessionRepository backed by Amazon ElastiCache for
    // Redis (configured in RedisSessionConfig). All session reads/writes use this repository
    // instead of the servlet-container-local HttpSession, enabling stateless instances.
    @Autowired
    private SessionRepository<? extends Session> sessionRepository;

    // cr-java-0067 FIX: Replaced the unbounded in-memory HashMap cache with a
    // RedisTemplate-backed cache connected to Amazon ElastiCache for Redis.
    // RedisTemplate is auto-configured by spring-boot-starter-data-redis using the
    // same ElastiCache connection factory defined in RedisSessionConfig.
    // Cache entries are written with a TTL (BOOKING_CACHE_TTL_SECONDS) so they
    // expire automatically — preventing indefinite memory growth and stale data.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0067 FIX: TTL for booking cache entries in seconds.
    // Injected from the environment variable BOOKING_CACHE_TTL_SECONDS or the
    // application property booking.cache.ttl-seconds (default: 3600 = 1 hour).
    // Storing the TTL externally allows operators to tune expiration per environment
    // without code changes (12-factor app principle III — Config).
    @Value("${booking.cache.ttl-seconds:3600}")
    private long bookingCacheTtlSeconds;

    // cr-java-0067 FIX: Redis key prefix for booking cache entries.
    // Namespacing keys prevents collisions with Spring Session keys stored in the
    // same ElastiCache cluster.
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Booking state is now stored in Amazon ElastiCache for Redis via
        // Spring Session's SessionRepository instead of the in-process HttpSession.
        // A new session is created (or an existing one is retrieved by sessionId) and the
        // booking attributes are persisted to Redis, making them available to any instance
        // in the cluster on subsequent requests.
        Session session = (sessionId != null) ? sessionRepository.findById(sessionId) : null;
        if (session == null) {
            session = sessionRepository.createSession();
        }
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);
        sessionRepository.save(session);

        // cr-java-0067 FIX: Cache the booking in Amazon ElastiCache for Redis with a TTL.
        // The key is namespaced with BOOKING_CACHE_KEY_PREFIX to avoid collisions.
        // opsForValue().set() with a timeout argument stores the entry with an automatic
        // expiration, preventing unbounded cache growth and ensuring stale entries are
        // evicted after bookingCacheTtlSeconds seconds.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        // Return the session ID so the client can pass it on subsequent requests.
        response.put("sessionId", session.getId());
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // cr-java-0065 FIX: Session state is now read from Amazon ElastiCache for Redis via
        // Spring Session's SessionRepository. The sessionId request parameter replaces the
        // former HttpSession parameter — any instance in the cluster can serve this request
        // because the session data lives in the shared Redis store, not in local JVM memory.
        String lastGuest = null;
        if (sessionId != null) {
            Session session = sessionRepository.findById(sessionId);
            if (session != null) {
                lastGuest = (String) session.getAttribute("guestName");
            }
        }

        // cr-java-0067 FIX: Booking lookup now checks the Redis cache first (cache-aside
        // pattern). If the entry is present and has not yet expired, it is returned
        // directly from ElastiCache without hitting the database. If the entry has expired
        // or was never cached, the request falls through to bookingService.getBookingById().
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (line 66): Replaced hard-coded environment-specific URL
        // "http://inventory-service.internal:8081/rooms/available" with a value retrieved
        // from AWS Systems Manager Parameter Store via AwsSsmConfig. The SSM parameter name
        // is configured through the environment variable INVENTORY_SERVICE_URL_PARAM
        // (default: /resortslite/inventory/service-url), enabling environment-agnostic
        // deployments without code changes between dev, staging, and production.
        String inventoryUrl = awsSsmConfig.getParameter(
                System.getenv().getOrDefault(
                        "INVENTORY_SERVICE_URL_PARAM",
                        "/resortslite/inventory/service-url"));

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
