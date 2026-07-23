package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0065: In-memory session cache replaced with Azure Cache for Redis
    // via Spring Session + RedisTemplate for stateless, horizontally scalable state management.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0071: Hard-coded inventory URL replaced with Azure App Configuration value
    @Value("${app.inventory.endpoint:${APP_INVENTORY_ENDPOINT:https://inventory-service.internal:8081/rooms/available}}")
    private String inventoryUrl;

    // Session TTL in seconds (30 minutes)
    private static final long SESSION_TTL_SECONDS = 1800L;

    /**
     * Creates a new booking and stores session state in Azure Cache for Redis.
     * cr-java-0065: HTTP session replaced with Redis-backed distributed session storage.
     *
     * @param guestName guest name
     * @param roomType  room type
     * @param checkIn   check-in date
     * @param checkOut  check-out date
     * @return booking confirmation response
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065: Store booking state in Azure Cache for Redis instead of HttpSession.
        // Redis is shared across all instances, enabling horizontal scaling and failover.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("booking:" + bookingId, booking, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set("session:lastBooking:" + guestName, booking, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set("session:guestName:" + bookingId, guestName, SESSION_TTL_SECONDS, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking, reading session state from Azure Cache for Redis.
     * cr-java-0065: HTTP session replaced with Redis-backed distributed session storage.
     *
     * @param bookingId the booking identifier
     * @return booking status response
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // cr-java-0065: Read session state from Redis — consistent across all instances
        String lastGuest = (String) redisTemplate.opsForValue().get("session:guestName:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using the externalized inventory service URL.
     * cr-java-0071: Hard-coded URL replaced with Azure App Configuration value.
     *
     * @param roomType room type to check
     * @return availability response
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071: inventoryUrl now loaded from Azure App Configuration / environment variable
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a report download reference using Azure Blob Storage.
     * cr-java-0061: Hard-coded local file path replaced with Azure Blob Storage URL.
     *
     * @param month the month for the report
     * @return report download response
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // cr-java-0061: Local file path replaced with Azure Blob Storage reference
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
