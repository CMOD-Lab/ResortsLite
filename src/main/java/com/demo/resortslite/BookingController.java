package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * REST controller for booking operations.
 * Session state is externalized to Azure Cache for Redis (cr-java-0065).
 * In-memory cache replaced with Azure Cache for Redis with TTL (cr-java-0067).
 * Hard-coded environment URL externalized to Azure App Configuration (cr-java-0071).
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067: In-memory cache replaced with Azure Cache for Redis via RedisTemplate.
    // TTL is applied on every cache write to prevent unbounded memory growth.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0067: Cache TTL in minutes — externalized so it can be tuned per environment.
    @Value("${app.cache.booking.ttl-minutes:60}")
    private long bookingCacheTtlMinutes;

    // cr-java-0071: Hard-coded inventory URL replaced with Azure App Configuration value.
    @Value("${app.inventory.endpoint:${INVENTORY_ENDPOINT:https://inventory-service.internal/rooms/available}}")
    private String inventoryEndpoint;

    /**
     * Creates a new booking and stores session state in Azure Cache for Redis.
     * Replaces in-memory HTTP session storage (cr-java-0065) and local HashMap cache (cr-java-0067).
     *
     * @param guestName  the guest's name
     * @param roomType   the type of room requested
     * @param checkIn    the check-in date
     * @param checkOut   the check-out date
     * @param sessionId  a client-supplied or token-derived session identifier
     * @return confirmation response with booking details
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065: Session state stored in Azure Cache for Redis instead of HttpSession.
        // This enables stateless horizontal scaling across multiple instances.
        if (!sessionId.isEmpty()) {
            redisTemplate.opsForValue().set(
                    "session:" + sessionId + ":lastBooking", booking,
                    bookingCacheTtlMinutes, TimeUnit.MINUTES);
            redisTemplate.opsForValue().set(
                    "session:" + sessionId + ":guestName", guestName,
                    bookingCacheTtlMinutes, TimeUnit.MINUTES);
        }

        // cr-java-0067: Booking cached in Redis with TTL — replaces static in-memory HashMap.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set(
                "booking:" + bookingId, booking,
                bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves booking status, reading session context from Azure Cache for Redis.
     * Replaces in-memory HTTP session reads (cr-java-0065).
     *
     * @param bookingId the booking identifier
     * @param sessionId a client-supplied or token-derived session identifier
     * @return booking status and session context
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "") String sessionId) {

        // cr-java-0065: Session data retrieved from Redis — consistent across all instances.
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
     * Checks room availability using the externalized inventory service URL.
     * Replaces hard-coded environment URL (cr-java-0071).
     *
     * @param roomType the type of room to check
     * @return availability response
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071: Inventory URL loaded from Azure App Configuration / environment variable.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a report download reference using Azure Blob Storage paths.
     * Replaces hard-coded local file path (cr-java-0061).
     *
     * @param month the month for the report
     * @return report reference response
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // cr-java-0061: Hard-coded local path replaced — report is now stored in Azure Blob Storage.
        String blobName = month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("blobName", blobName);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
