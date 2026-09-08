package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController handles HTTP requests for resort booking operations.
 * HTTP session state has been replaced with Amazon ElastiCache for Redis
 * via Spring Session to enable stateless, horizontally scalable instances.
 * In-memory caching has been replaced with Redis-backed distributed caching with TTL.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX:
    // Replaced unbounded in-memory HashMap cache (bookingCache) with Amazon ElastiCache
    // for Redis via Spring's RedisTemplate. Redis provides TTL-based expiration,
    // consistent data across all instances, and centralized cache management.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL in minutes — configurable via environment variable / application properties
    @Value("${app.cache.booking-ttl-minutes:60}")
    private long bookingCacheTtlMinutes;

    // cr-java-0071 FIX:
    // Replaced hard-coded "http://inventory-service.internal:8081/rooms/available" URL
    // with value injected from application properties / environment variable,
    // which is sourced from AWS Systems Manager Parameter Store.
    @Value("${app.inventory.endpoint:${INVENTORY_SERVICE_URL:https://inventory-service.internal/rooms/available}}")
    private String inventoryUrl;

    /**
     * Creates a new booking and stores session state in Amazon ElastiCache for Redis.
     *
     * @param guestName the name of the guest
     * @param roomType  the type of room
     * @param checkIn   the check-in date
     * @param checkOut  the check-out date
     * @return a map containing the booking confirmation
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX:
        // Replaced HttpSession.setAttribute("lastBooking", booking) and
        // HttpSession.setAttribute("guestName", guestName) with Redis-backed storage
        // via Spring Session / RedisTemplate. This enables stateless application instances
        // with centralized, distributed session management across all EC2/ECS instances.
        String bookingId = (String) booking.get("bookingId");
        String sessionKey = "session:lastBooking:" + bookingId;
        String guestKey = "session:guestName:" + bookingId;

        redisTemplate.opsForValue().set(sessionKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(guestKey, guestName, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        // cr-java-0067 FIX:
        // Replaced static in-memory bookingCache.put() with Redis cache entry with TTL.
        String cacheKey = "cache:booking:" + bookingId;
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves the status of a booking, reading session state from Redis.
     *
     * @param bookingId the booking identifier
     * @return a map containing the booking status and session guest information
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // cr-java-0065 FIX:
        // Replaced HttpSession.getAttribute("guestName") with Redis lookup.
        // Session data is now available across all instances in the cluster.
        String guestKey = "session:guestName:" + bookingId;
        String lastGuest = (String) redisTemplate.opsForValue().get(guestKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using the externalized inventory service URL.
     *
     * @param roomType the type of room to check
     * @return a map containing availability information
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX:
        // inventoryUrl is now injected from application properties / environment variable
        // sourced from AWS Systems Manager Parameter Store, replacing the hard-coded
        // "http://inventory-service.internal:8081/rooms/available" URL.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a download link for the monthly booking report.
     *
     * @param month the month for the report
     * @return a map containing the report S3 reference and generation message
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Hard-coded local file path removed; report storage is now handled by
        // ReportService which writes to Amazon S3. The S3 key is returned instead.
        String s3ReportKey = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("s3ReportKey", s3ReportKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
