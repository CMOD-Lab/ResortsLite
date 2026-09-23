package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final Duration BOOKING_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration SESSION_STATE_TTL = Duration.ofHours(2);

    @Autowired
    private BookingService bookingService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${app.inventory.url:https://inventory-service.internal/rooms/available}")
    private String inventoryUrl;

    @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download/}")
    private String reportDownloadBaseUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String bookingId = (String) booking.get("bookingId");
        String resolvedSessionId = resolveSessionId(sessionId, bookingId);

        redisTemplate.opsForValue().set(buildSessionKey(resolvedSessionId, "lastBooking"), booking.toString(), SESSION_STATE_TTL);
        redisTemplate.opsForValue().set(buildSessionKey(resolvedSessionId, "guestName"), guestName, SESSION_STATE_TTL);
        redisTemplate.opsForValue().set(buildBookingCacheKey(bookingId), booking.toString(), BOOKING_CACHE_TTL);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        response.put("sessionId", resolvedSessionId);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        String resolvedSessionId = resolveSessionId(sessionId, bookingId);
        String lastGuest = redisTemplate.opsForValue().get(buildSessionKey(resolvedSessionId, "guestName"));

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
        String normalizedBaseUrl = reportDownloadBaseUrl.endsWith("/")
                ? reportDownloadBaseUrl
                : reportDownloadBaseUrl + "/";
        String reportPath = normalizedBaseUrl + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    private String buildBookingCacheKey(String bookingId) {
        return "booking-cache:" + bookingId;
    }

    private String buildSessionKey(String sessionId, String attribute) {
        return "booking-session:" + sessionId + ":" + attribute;
    }

    private String resolveSessionId(String sessionId, String fallback) {
        return (sessionId == null || sessionId.trim().isEmpty()) ? fallback : sessionId;
    }
}
