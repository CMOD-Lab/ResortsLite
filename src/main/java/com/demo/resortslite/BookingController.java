package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// cr-java-0065 remediation: HTTP session state has been replaced with Azure Cache for Redis.
// RedisTemplate is used to store and retrieve session-scoped booking data, enabling stateless
// application architecture and horizontal scaling across multiple instances.  All instances
// share the same Redis store, so load-balanced requests and auto-scaling work correctly.
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0065 remediation: RedisTemplate replaces HttpSession for distributed,
    // instance-agnostic state storage backed by Azure Cache for Redis.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0067 remediation: The static in-memory bookingCache (HashMap) has been removed.
    // All booking cache entries are now stored in Azure Cache for Redis via RedisTemplate
    // with an explicit TTL of 60 minutes, preventing indefinite memory growth, eliminating
    // stale data inconsistencies, and ensuring cache visibility across all application
    // instances in a horizontally scaled cloud deployment.

    // cr-java-0071 remediation: Externalize hard-coded inventory service URL to Azure App
    // Configuration. The URL is injected from application properties / environment variables
    // so it can differ per deployment environment without any code change.
    @Value("${app.inventory.availability-endpoint:${app.inventory.endpoint}/available}")
    private String inventoryAvailabilityUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 remediation: Booking state is now stored in Azure Cache for Redis
        // instead of the in-memory HTTP session.  A TTL of 30 minutes mirrors a typical
        // session lifetime and ensures stale entries are automatically evicted.
        // All application instances share the same Redis store, so any instance can serve
        // subsequent requests for this guest without sticky sessions or session replication.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("session:lastBooking:" + bookingId, booking, 30, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set("session:guestName:" + bookingId, guestName, 30, TimeUnit.MINUTES);

        // cr-java-0067 remediation: Booking data is cached in Azure Cache for Redis with a
        // 60-minute TTL instead of the former static in-memory HashMap (bookingCache).
        // Using Redis ensures the cache is shared across all application instances, prevents
        // unbounded memory growth, and automatically evicts stale entries after the TTL expires.
        redisTemplate.opsForValue().set("cache:booking:" + bookingId, booking, 60, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // cr-java-0065 remediation: Guest name is now retrieved from Azure Cache for Redis
        // instead of the in-memory HTTP session.  This works correctly regardless of which
        // application instance handles the request, enabling true horizontal scalability.
        String lastGuest = (String) redisTemplate.opsForValue().get("session:guestName:" + bookingId);

        // cr-java-0067 remediation: Booking details are retrieved from Azure Cache for Redis
        // (distributed cache with TTL) instead of the former static in-memory HashMap.
        // If the cache entry has expired or is absent, the request falls through to the
        // bookingService to fetch the authoritative record from the database.
        Object cachedBooking = redisTemplate.opsForValue().get("cache:booking:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("cachedBooking", cachedBooking);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 remediation: Hard-coded environment URL replaced with value injected
        // from Azure App Configuration via @Value("${app.inventory.availability-endpoint}").
        // The endpoint is now environment-agnostic and can be overridden per deployment
        // environment (dev / staging / production) without modifying source code.
        String inventoryUrl = inventoryAvailabilityUrl; // cr-java-0071 fixed

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
