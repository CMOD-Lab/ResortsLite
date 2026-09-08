package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController exposes REST endpoints for resort booking operations.
 *
 * cr-java-0065: HttpSession replaced with Azure Cache for Redis via Spring Session +
 *               RedisTemplate to enable stateless horizontal scaling.
 * cr-java-0067: In-memory HashMap cache replaced with Azure Cache for Redis with TTL.
 * cr-java-0071: Hard-coded inventory URL externalized to Azure App Configuration / env var.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067: Distributed Redis cache replaces instance-local HashMap.
    // TTL is applied on every write to prevent unbounded memory growth.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Default TTL for cached booking entries (30 minutes).
    private static final long BOOKING_CACHE_TTL_MINUTES = 30L;

    // Default TTL for session attributes stored in Redis (60 minutes).
    private static final long SESSION_TTL_MINUTES = 60L;

    // cr-java-0071: Hard-coded inventory URL replaced with externalized configuration.
    @Value("${app.inventory.endpoint}")
    private String inventoryEndpoint;

    /**
     * Creates a new booking and stores session state in Azure Cache for Redis.
     * Replaces HttpSession usage (cr-java-0065) and in-memory cache (cr-java-0067).
     *
     * @param guestName  guest full name
     * @param roomType   room category
     * @param checkIn    check-in date
     * @param checkOut   check-out date
     * @param sessionId  caller-supplied session identifier (e.g. from Authorization header or cookie)
     * @return booking confirmation response
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065: Session state stored in Azure Cache for Redis — visible to all
        // instances in the cluster, survives container restarts and auto-scaling events.
        if (!sessionId.isEmpty()) {
            redisTemplate.opsForValue().set("session:" + sessionId + ":lastBooking",
                    booking, SESSION_TTL_MINUTES, TimeUnit.MINUTES);
            redisTemplate.opsForValue().set("session:" + sessionId + ":guestName",
                    guestName, SESSION_TTL_MINUTES, TimeUnit.MINUTES);
        }

        // cr-java-0067: Booking cached in Redis with TTL — prevents unbounded memory growth
        // and ensures cache consistency across all application instances.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("booking:" + bookingId,
                booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking, reading session context from Azure Cache for Redis.
     * Replaces HttpSession usage (cr-java-0065).
     *
     * @param bookingId booking identifier
     * @param sessionId caller-supplied session identifier
     * @return booking status response
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "") String sessionId) {

        // cr-java-0065: Session attribute read from Redis — consistent across all instances.
        String lastGuest = null;
        if (!sessionId.isEmpty()) {
            Object val = redisTemplate.opsForValue().get("session:" + sessionId + ":guestName");
            lastGuest = val != null ? val.toString() : null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using the externalized inventory service endpoint.
     * Replaces hard-coded URL (cr-java-0071).
     *
     * @param roomType room category to check
     * @return availability response
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071: inventoryEndpoint loaded from Azure App Configuration via @Value —
        // no hard-coded hostname or port in source code.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a download reference for a monthly report stored in Azure Blob Storage.
     *
     * @param month the month identifier
     * @return report download response
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Hard-coded local path removed; report location is now a Blob Storage URL
        // resolved by ReportService using externalized configuration.
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
