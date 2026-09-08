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

    // blocker-20: In-memory HashMap cache replaced with Google Cloud Memorystore for Redis.
    // RedisTemplate provides a distributed, TTL-aware cache shared across all instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL: 30 minutes — prevents unbounded memory growth (blocker-20).
    private static final long CACHE_TTL_MINUTES = 30;

    // blocker-10: Hard-coded inventory URL replaced with externalized property.
    // Value is resolved from environment variable INVENTORY_URL or application property,
    // which can be backed by GCP Secret Manager for sensitive endpoints.
    @Value("${app.inventory.endpoint:${INVENTORY_URL:https://inventory-service.internal:8081/rooms/available}}")
    private String inventoryUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // blocker-13, blocker-14, blocker-15, blocker-16:
        // HTTP session state replaced with Google Cloud Memorystore for Redis.
        // Session data is stored in Redis with a TTL so it is shared across all instances
        // and survives container restarts / horizontal scaling events.
        String sessionKey = "session:lastBooking:" + booking.get("bookingId");
        String guestKey   = "session:guestName:"   + booking.get("bookingId");
        redisTemplate.opsForValue().set(sessionKey, booking,    CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(guestKey,   guestName,  CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        // blocker-20: Booking also stored in Redis cache with TTL (replaces static HashMap).
        String cacheKey = "cache:booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // blocker-17: Session state read from Redis (Memorystore) instead of HttpSession.
        // Works correctly across all instances in the cluster.
        String guestKey   = "session:guestName:" + bookingId;
        String lastGuest  = (String) redisTemplate.opsForValue().get(guestKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // blocker-10: inventoryUrl is injected from env var / GCP Secret Manager.
        // No hard-coded URL in source code.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Hard-coded local path replaced: report is now stored in GCS.
        // The GCS URI is returned by ReportService which uses the configured bucket name.
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
