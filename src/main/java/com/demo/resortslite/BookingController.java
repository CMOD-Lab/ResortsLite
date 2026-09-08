package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
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

    // blocker-13 (cz-java-0070): Replaced local in-memory HashMap cache with Redis-backed
    // distributed cache via RedisTemplate to support horizontal scaling across container instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // blocker-4 (cz-java-0063): Removed import javax.servlet.http.HttpSession — server-side
    // HttpSession replaced with Spring Session backed by Azure Cache for Redis.
    // blocker-5 (cz-java-0063): Removed HttpSession parameter from createBooking method.
    // blocker-7 (cz-java-0069): Replaced session.setAttribute("lastBooking", ...) with Redis store.
    // blocker-8 (cz-java-0069): Replaced session.setAttribute("guestName", ...) with Redis store.
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // blocker-7 (cz-java-0069) & blocker-8 (cz-java-0069): Store session state in Redis
        // instead of in-memory HttpSession so state survives container restarts and scales.
        String sessionKey = "session:booking:" + booking.get("bookingId");
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
        redisTemplate.expire(sessionKey, 30, TimeUnit.MINUTES);

        // blocker-13 (cz-java-0070): Store in distributed Redis cache instead of local HashMap.
        String cacheKey = "cache:booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, 30, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    // blocker-6 (cz-java-0063): Removed HttpSession parameter from getBookingStatus method.
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // blocker-6 (cz-java-0063): Read session state from Redis instead of HttpSession.
        String sessionKey = "session:booking:" + bookingId;
        String lastGuest = (String) redisTemplate.opsForHash().get(sessionKey, "guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    // blocker-1 (cz-java-0057): Replaced hardcoded absolute path "/var/legacy/reports/" with
    // environment variable REPORT_BASE_PATH injected via AKS ConfigMap / Azure App Configuration.
    // blocker-9 (cz-java-0082): Decoupled report generation — BookingController now delegates
    // to BookingService.generateReport() without embedding path logic directly in the controller.
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        String reportBasePath = System.getenv().getOrDefault("REPORT_BASE_PATH", "/reports");
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
