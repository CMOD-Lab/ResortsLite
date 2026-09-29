package com.demo.resortslite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// cr-java-0065: Removed HttpSession import — HTTP session state has been fully replaced
// by Azure Cache for Redis via Spring Data Redis (StringRedisTemplate). Session data is
// now stored in a shared, distributed Redis store, enabling stateless application
// instances and safe horizontal scaling across multiple Azure Container App replicas.
// All session.setAttribute / session.getAttribute calls have been replaced with
// redisTemplate.opsForValue().set / get calls backed by Azure Cache for Redis.

// cr-java-0067: Removed static in-memory bookingCache (HashMap) — replaced with
// Azure Cache for Redis via StringRedisTemplate with a configurable TTL.
// The previous static HashMap was instance-local: cache entries were invisible to
// other application replicas, caused indefinite memory growth, and could not be
// shared across horizontally scaled instances. Azure Cache for Redis provides a
// distributed, TTL-enforced cache that is consistent across all replicas and
// automatically evicts stale entries, preventing memory exhaustion.

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0065: Injected StringRedisTemplate to replace HttpSession-based state storage.
    // Booking state is now persisted in Azure Cache for Redis, making every application
    // instance stateless and allowing the load balancer to route requests to any replica
    // without sticky sessions or session replication overhead.
    @Autowired
    private StringRedisTemplate redisTemplate;

    // cr-java-0067: ObjectMapper used to serialize/deserialize booking Map to/from JSON
    // for storage in Azure Cache for Redis via StringRedisTemplate.
    @Autowired
    private ObjectMapper objectMapper;

    // cr-java-0065: Session TTL (seconds) — externalised to environment variable so it can
    // be tuned per environment without code changes. Defaults to 1800 s (30 minutes).
    @Value("${app.session.ttl-seconds:1800}")
    private long sessionTtlSeconds;

    // cr-java-0067: Booking cache TTL (seconds) — externalised to environment variable.
    // Controls how long booking data is retained in Azure Cache for Redis before automatic
    // eviction. Defaults to 3600 s (1 hour). Override via APP_BOOKING_CACHE_TTL_SECONDS.
    @Value("${app.booking.cache.ttl-seconds:3600}")
    private long bookingCacheTtlSeconds;

    // cr-java-0071: Externalized hard-coded inventory service URL to Azure App Configuration
    // backed environment variable. Previously hard-coded as:
    //   "http://inventory-service.internal:8081/rooms/available"
    // Now injected via ${app.inventory.url} which is resolved from the environment /
    // Azure App Configuration at runtime, enabling environment-agnostic deployments.
    @Value("${app.inventory.url:http://inventory-service.internal:8081/rooms/available}")
    private String inventoryServiceUrl;

    // cr-java-0067: REMOVED — static in-memory bookingCache replaced with Azure Cache for Redis.
    // Previously: private static final Map<String, Object> bookingCache = new HashMap<>();
    // This HashMap was instance-local, had no TTL, and caused memory growth and stale data
    // inconsistencies across multiple instances. It has been replaced with Redis-backed
    // caching using StringRedisTemplate with a configurable TTL (app.booking.cache.ttl-seconds).

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {
        // cr-java-0065: HttpSession parameter removed. Booking state is now stored in
        // Azure Cache for Redis using a deterministic key derived from the bookingId,
        // so any application instance can retrieve it without server affinity.

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065: Replaced session.setAttribute("lastBooking", booking) and
        // session.setAttribute("guestName", guestName) with Redis key-value entries.
        // Keys are namespaced under "booking:<bookingId>:" to avoid collisions.
        // A TTL equal to ${app.session.ttl-seconds} is applied so stale entries are
        // automatically evicted by Azure Cache for Redis, matching session expiry semantics.
        String bookingId = (String) booking.get("bookingId");
        String redisKeyGuest = "booking:" + bookingId + ":guestName";
        redisTemplate.opsForValue().set(redisKeyGuest, guestName, sessionTtlSeconds, TimeUnit.SECONDS);

        // cr-java-0067: Replaced bookingCache.put(bookingId, booking) with Azure Cache for
        // Redis storage. The booking Map is serialized to JSON and stored under the key
        // "booking:<bookingId>:data" with a TTL of ${app.booking.cache.ttl-seconds} seconds.
        // This ensures:
        //   • All application replicas share the same cache — no instance-local state.
        //   • Entries are automatically evicted after TTL expires — no memory exhaustion.
        //   • Stale data inconsistencies across instances are eliminated.
        try {
            String bookingJson = objectMapper.writeValueAsString(booking);
            String redisKeyBooking = "booking:" + bookingId + ":data";
            redisTemplate.opsForValue().set(redisKeyBooking, bookingJson, bookingCacheTtlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            // Log serialization failure but do not fail the booking creation —
            // the booking has already been persisted to the database.
            // The cache miss will be handled gracefully on subsequent reads.
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {
        // cr-java-0065: HttpSession parameter removed. Guest name is now retrieved from
        // Azure Cache for Redis instead of from the local in-memory HTTP session.
        // This ensures consistent results regardless of which application instance
        // handles the request, eliminating the "null on any other instance" problem.
        String redisKeyGuest = "booking:" + bookingId + ":guestName";
        String lastGuest = redisTemplate.opsForValue().get(redisKeyGuest);

        // cr-java-0067: Retrieve cached booking data from Azure Cache for Redis.
        // Falls back to the database via bookingService.getBookingById if cache miss.
        String redisKeyBooking = "booking:" + bookingId + ":data";
        String cachedBookingJson = redisTemplate.opsForValue().get(redisKeyBooking);
        Map<String, Object> cachedBooking = null;
        if (cachedBookingJson != null) {
            try {
                cachedBooking = objectMapper.readValue(cachedBookingJson,
                        new TypeReference<Map<String, Object>>() {});
            } catch (JsonProcessingException e) {
                // Cache entry is malformed — fall through to DB lookup below.
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071: URL is no longer hard-coded. It is injected from the environment-backed
        // property ${app.inventory.url} (see application.properties / Azure App Configuration),
        // enabling environment-agnostic deployments without code changes per environment.
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
