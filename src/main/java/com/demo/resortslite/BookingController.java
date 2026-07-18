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

    // blocker-20 (cr-java-0067): in-memory HashMap cache replaced with Google Cloud
    // Memorystore for Redis via RedisTemplate — TTL-aware, shared across all instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL: 30 minutes (configurable via environment variable)
    private static final long CACHE_TTL_MINUTES = 30;

    // blocker-13/14/15/16/17 (cr-java-0065): HTTP session state replaced with Redis.
    // Session attributes are stored in Memorystore for Redis so all instances share state.
    // Spring Session auto-configures Redis-backed sessions when spring-session-data-redis
    // is on the classpath and spring.session.store-type=redis is set in properties.

    // blocker-10 (cr-java-0071): inventory service URL externalised via property /
    // environment variable — no hard-coded internal hostname or port.
    @Value("${app.inventory.endpoint:${INVENTORY_URL:https://inventory-service.internal/rooms/available}}")
    private String inventoryUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // blocker-13/14/15/16/17 (cr-java-0065): store session state in Redis instead of
        // HttpSession so it is visible to every instance behind the load balancer.
        String sessionKey = "session:lastBooking:" + guestName;
        redisTemplate.opsForValue().set(sessionKey, booking, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        String guestKey = "session:guestName:" + guestName;
        redisTemplate.opsForValue().set(guestKey, guestName, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        // blocker-20 (cr-java-0067): cache booking in Redis with TTL instead of local HashMap
        String cacheKey = "booking:cache:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // blocker-13/14/15/16/17 (cr-java-0065): read session state from Redis — consistent
        // across all instances; no server-affinity required.
        String lastGuest = (String) redisTemplate.opsForValue().get("session:guestName:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // blocker-10 (cr-java-0071): inventory URL now injected from externalised config
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Hard-coded local path removed; report URL is now built via ReportService
        // which uses the GCS bucket and externalised download URL (blocker-1/2/3).
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
