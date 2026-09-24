package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final Duration BOOKING_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration SESSION_STATE_TTL = Duration.ofMinutes(30);

    @Autowired
    private BookingService bookingService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${app.inventory.base-url:https://inventory-service.internal/rooms/available}")
    private String inventoryUrl;

    @Value("${app.reports.base-url:https://reports-storage.example.com/reports}")
    private String reportBaseUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        String sessionKey = resolveSessionKey(session);
        ValueOperations<String, String> ops = redisTemplate.opsForValue();
        ops.set(sessionKey + ":lastBooking", booking.get("bookingId").toString(), SESSION_STATE_TTL);
        ops.set(sessionKey + ":guestName", guestName, SESSION_STATE_TTL);
        ops.set("booking:cache:" + booking.get("bookingId"), booking.toString(), BOOKING_CACHE_TTL);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        response.put("sessionKey", sessionKey);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        String sessionKey = resolveSessionKey(session);
        String lastGuest = redisTemplate.opsForValue().get(sessionKey + ":guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        String reportPath = appendPath(reportBaseUrl, month + "_bookings.pdf");

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    private String resolveSessionKey(HttpSession session) {
        Object existing = session.getAttribute("redisSessionKey");
        if (existing != null) {
            return existing.toString();
        }
        String sessionKey = "session:" + UUID.randomUUID();
        session.setAttribute("redisSessionKey", sessionKey);
        return sessionKey;
    }

    private String appendPath(String baseUrl, String suffix) {
        return baseUrl.endsWith("/") ? baseUrl + suffix : baseUrl + "/" + suffix;
    }
}
