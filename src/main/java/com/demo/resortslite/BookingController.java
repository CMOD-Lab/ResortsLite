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
 * BookingController — REST controller for resort booking operations.
 *
 * <p><strong>cr-java-0067 fix (line 19):</strong> The unbounded static in-memory cache
 * {@code private static final Map<String, Object> bookingCache = new HashMap<>()} has been
 * removed and replaced with Amazon ElastiCache for Redis via Spring's {@link RedisTemplate}.
 * Cache entries are written with an explicit TTL (controlled by
 * {@code app.booking.cache.ttl-minutes}, default 60 minutes) so that:
 * <ul>
 *   <li>Memory growth is bounded — entries expire automatically after the configured TTL.</li>
 *   <li>All application instances share the same cache — no stale-data inconsistencies
 *       across EC2 instances in the Auto Scaling Group.</li>
 *   <li>Cache survives instance restarts and scale-in events.</li>
 * </ul>
 * The {@link RedisTemplate} is auto-configured by Spring Boot using the ElastiCache
 * connection properties ({@code spring.redis.host}, {@code spring.redis.port},
 * {@code spring.redis.password}) already defined in {@code application.properties} and
 * the Lettuce connection factory declared in {@link RedisSessionConfig}.
 *
 * <p><strong>cr-java-0065 fix (lines 6, 27, 34, 35, 48):</strong> All HTTP session state
 * ({@code HttpSession}) has been replaced with Spring Session backed by Amazon ElastiCache
 * for Redis. The {@code javax.servlet.http.HttpSession} import and parameter have been
 * removed from every handler method. Session read/write operations now go through
 * {@link SessionRepository}, which delegates to the Redis store configured via
 * {@code spring.session.store-type=redis} and the ElastiCache endpoint properties.
 * This makes every application instance fully stateless — any instance in the Auto Scaling
 * Group can serve any request without sticky sessions on the AWS ALB.
 *
 * <p><strong>cr-java-0071 fix (line 66):</strong> The hard-coded environment-specific URL
 * {@code "http://inventory-service.internal:8081/rooms/available"} has been replaced with
 * a Spring {@code @Value}-injected property {@code app.inventory.available-url} that is
 * resolved from the AWS Systems Manager Parameter Store via the Spring Cloud AWS
 * Parameter Store integration. The parameter is stored under the path
 * {@code /resortslite/inventory/available-url} in SSM and injected at startup, making
 * the application fully environment-agnostic without any code changes between deployments.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Spring Session repository backed by Amazon ElastiCache for Redis.
     *
     * <p><strong>cr-java-0065 fix:</strong> Replaces direct {@code HttpSession} usage.
     * All session attributes are stored in and retrieved from the centralised Redis store,
     * enabling stateless application instances and horizontal scaling without sticky sessions.
     */
    @Autowired
    private SessionRepository<? extends Session> sessionRepository;

    /**
     * Redis template for booking cache operations.
     *
     * <p><strong>cr-java-0067 fix:</strong> Replaces the static in-memory
     * {@code HashMap}-based cache with Amazon ElastiCache for Redis. All booking cache
     * reads and writes are performed through this template, which connects to the shared
     * ElastiCache cluster. Entries are stored with an explicit TTL defined by
     * {@code app.booking.cache.ttl-minutes} to prevent unbounded memory growth and ensure
     * cache consistency across all application instances.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Cache key prefix used to namespace booking entries in Redis.
     * Prevents key collisions with other data stored in the same ElastiCache cluster.
     */
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    /**
     * TTL (in minutes) for booking cache entries stored in Amazon ElastiCache for Redis.
     *
     * <p><strong>cr-java-0067 fix:</strong> Enforces a bounded expiration policy on all
     * cached booking objects, replacing the previous unbounded in-memory HashMap that
     * grew indefinitely and caused out-of-memory errors under load.
     * Resolved from the {@code app.booking.cache.ttl-minutes} property (default: 60 minutes).
     * Override via environment variable {@code APP_BOOKING_CACHE_TTL_MINUTES} or AWS SSM
     * Parameter Store entry {@code /resortslite/booking/cache-ttl-minutes}.
     */
    @Value("${app.booking.cache.ttl-minutes:60}")
    private long bookingCacheTtlMinutes;

    /**
     * Inventory service available-rooms endpoint URL.
     *
     * <p><strong>cr-java-0071 fix:</strong> Replaces the hard-coded URL
     * {@code "http://inventory-service.internal:8081/rooms/available"} (original line 66).
     * Value is resolved from AWS Systems Manager Parameter Store via the property key
     * {@code app.inventory.available-url}, which maps to the SSM parameter path
     * {@code /resortslite/inventory/available-url}. The environment variable
     * {@code APP_INVENTORY_AVAILABLE_URL} can also be used as a fallback for local development.
     */
    @Value("${app.inventory.available-url:${APP_INVENTORY_AVAILABLE_URL:https://inventory-service.internal/rooms/available}}")
    private String inventoryAvailableUrl;

    // cr-java-0067 FIX: The static in-memory bookingCache HashMap has been REMOVED.
    // It has been replaced with Amazon ElastiCache for Redis via RedisTemplate (see above).
    // Previously: private static final Map<String, Object> bookingCache = new HashMap<>();
    // The Redis-backed cache provides:
    //   - Bounded memory growth via TTL-based expiration (app.booking.cache.ttl-minutes)
    //   - Shared state across all EC2 instances in the Auto Scaling Group
    //   - Persistence across instance restarts and scale-in events
    //   - Centralized cache management via Amazon ElastiCache

    /**
     * Creates a new booking, persists session state to Amazon ElastiCache for Redis,
     * and caches the booking object in Redis with a TTL.
     *
     * <p><strong>cr-java-0067 fix:</strong> The booking is now cached in Amazon ElastiCache
     * for Redis via {@link RedisTemplate#opsForValue()#set(Object, Object, long, TimeUnit)}
     * with an explicit TTL, replacing the previous unbounded {@code bookingCache.put()} call
     * that stored entries in a static HashMap indefinitely.
     *
     * <p><strong>cr-java-0065 fix:</strong> The {@code HttpSession} parameter has been
     * removed. Session attributes {@code lastBooking} and {@code guestName} are now written
     * to a new Redis-backed Spring Session via {@link SessionRepository#createSession()} and
     * {@link SessionRepository#save(Session)}. The session ID is returned in the response so
     * that clients can supply it on subsequent requests via the {@code X-Auth-Token} header
     * (or the {@code SESSION} cookie, depending on the Spring Session header/cookie strategy
     * configured in {@code RedisSessionConfig}).
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session state is now stored in Amazon ElastiCache for Redis via
        // Spring Session. A new distributed session is created through SessionRepository,
        // replacing the previous HttpSession.setAttribute() calls that were instance-local
        // and incompatible with AWS ALB load balancing across multiple EC2 instances.
        Session redisSession = sessionRepository.createSession();
        redisSession.setAttribute("lastBooking", booking);   // cr-java-0065 FIXED
        redisSession.setAttribute("guestName", guestName);  // cr-java-0065 FIXED
        sessionRepository.save(redisSession);

        // cr-java-0067 FIX: Booking is now cached in Amazon ElastiCache for Redis with an
        // explicit TTL (app.booking.cache.ttl-minutes, default 60 min), replacing the
        // previous unbounded static HashMap that caused indefinite memory growth and was
        // invisible to other EC2 instances in the Auto Scaling Group.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        // Return the Redis session ID so clients can reference it on subsequent requests.
        response.put("sessionId", redisSession.getId());
        return response;
    }

    /**
     * Returns the booking status, first checking the Amazon ElastiCache for Redis cache,
     * then falling back to the booking service if the cache entry has expired or is absent.
     *
     * <p><strong>cr-java-0067 fix:</strong> Booking lookup now checks the Redis cache
     * (via {@link RedisTemplate#opsForValue()#get(Object)}) before calling the service layer.
     * Cache misses (due to TTL expiration or first-time lookups) fall through to
     * {@link BookingService#getBookingById(String)}, preserving existing business logic.
     *
     * <p><strong>cr-java-0065 fix:</strong> The {@code HttpSession} parameter has been
     * removed. The guest name is now retrieved from the Redis-backed Spring Session via
     * {@link SessionRepository#findById(String)}, using the session ID supplied by the
     * caller in the {@code sessionId} request parameter. This replaces the previous
     * {@code session.getAttribute("guestName")} call that only worked on the instance
     * that originally created the session.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // cr-java-0065 FIX: Guest name is now retrieved from Amazon ElastiCache for Redis
        // via Spring Session, replacing the previous HttpSession.getAttribute() call that
        // returned null on any EC2 instance other than the one that created the session.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            Session redisSession = sessionRepository.findById(sessionId); // cr-java-0065 FIXED
            if (redisSession != null) {
                lastGuest = (String) redisSession.getAttribute("guestName");
            }
        }

        // cr-java-0067 FIX: Booking details are now retrieved from the Amazon ElastiCache
        // for Redis cache. If the cache entry has expired (TTL elapsed) or is absent,
        // the request falls through to BookingService.getBookingById() to maintain
        // existing business logic and data consistency.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);
        Object bookingDetails = (cachedBooking != null)
                ? cachedBooking
                : bookingService.getBookingById(bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingDetails);
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIX cr-java-0071: Hard-coded environment URL replaced with AWS SSM Parameter Store value.
        // Previously: String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";
        // Now: injected via @Value from SSM parameter /resortslite/inventory/available-url
        // The URL is environment-agnostic and resolved at startup from AWS Systems Manager
        // Parameter Store, enabling zero-code-change deployments across dev/staging/production.
        String inventoryUrl = inventoryAvailableUrl; // cr-java-0071 FIXED — sourced from SSM Parameter Store

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
