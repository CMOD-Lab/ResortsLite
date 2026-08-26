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

    // blocker-13 (cz-java-0070): Replaced local in-memory HashMap cache with Redis-backed
    // distributed cache via RedisTemplate to support horizontal scaling across container instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // blocker-4 (cz-java-0063): Removed import javax.servlet.http.HttpSession — server-side
    // session replaced with Spring Session backed by Amazon ElastiCache (Redis).
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
        // instead of in-memory HttpSession so data survives container restarts and is shared
        // across all horizontally-scaled instances.
        String sessionKey = "session:booking:" + booking.get("bookingId");
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
        redisTemplate.expire(sessionKey, 30, TimeUnit.MINUTES);

        // blocker-13 (cz-java-0070): Store booking in Redis distributed cache instead of
        // local HashMap so all container instances share the same cache state.
        redisTemplate.opsForValue().set("cache:booking:" + booking.get("bookingId"), booking, 30, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    // blocker-6 (cz-java-0063): Removed HttpSession parameter from getBookingStatus method.
    // Session data is now retrieved from Redis (ElastiCache) instead of in-memory HttpSession.
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // blocker-6 (cz-java-0063): Read session state from Redis instead of HttpSession
        // so the value is consistent across all container replicas.
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
    // environment-variable-driven path injected via Kubernetes ConfigMap on EKS.
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // blocker-1 (cz-java-0057): Path now sourced from REPORT_BASE_PATH env var
        // (injected via EKS ConfigMap) instead of hardcoded "/var/legacy/reports/".
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        // blocker-9 (cz-java-0082): Decoupled report generation by delegating to BookingService
        // which itself delegates to ReportService via a loosely-coupled service interface,
        // enabling each component to be deployed as an independent EKS microservice.
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
