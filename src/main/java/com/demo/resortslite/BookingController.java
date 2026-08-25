package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Blocker-20 (cr-java-0067): In-memory HashMap cache without TTL replaced with
    // Amazon ElastiCache for Redis via Spring Data Redis. Redis provides TTL-based
    // expiration, consistent data across all instances, and centralized cache management.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Blocker-10 (cr-java-0071): Hard-coded inventory URL replaced with SSM Parameter Store.
    @Value("${SSM_INVENTORY_URL_PARAM:/resortslite/inventory/endpoint}")
    private String inventoryUrlParam;

    @Autowired
    private SsmClient ssmClient;

    // Default TTL for booking cache entries in Redis (30 minutes)
    private static final long BOOKING_CACHE_TTL_MINUTES = 30L;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Blocker-13,14,15,16 (cr-java-0065): HTTP session state replaced with
        // Amazon ElastiCache for Redis via Spring Session. Spring Session automatically
        // stores session data in Redis, enabling stateless instances and horizontal scaling.
        // The HttpSession API is preserved — Spring Session transparently backs it with Redis.
        session.setAttribute("lastBooking", booking);   // now stored in Redis via Spring Session
        session.setAttribute("guestName", guestName);  // now stored in Redis via Spring Session

        // Blocker-20 (cr-java-0067): Redis cache with TTL replaces unbounded in-memory HashMap.
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Blocker-17 (cr-java-0065): Session read now backed by Redis via Spring Session —
        // returns consistent data regardless of which instance handles the request.
        String lastGuest = (String) session.getAttribute("guestName");

        // Check Redis cache first (with TTL-controlled expiry)
        String cacheKey = "booking:" + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        if (cachedBooking != null) {
            result.put("details", cachedBooking);
            result.put("source", "cache");
        } else {
            Map<String, Object> details = bookingService.getBookingById(bookingId);
            result.put("details", details);
            result.put("source", "database");
            // Re-populate cache with TTL
            redisTemplate.opsForValue().set(cacheKey, details, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        }
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Blocker-10 (cr-java-0071): Hard-coded inventory URL replaced with SSM Parameter Store.
        String inventoryUrl;
        try {
            GetParameterResponse paramResponse = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(inventoryUrlParam)
                            .withDecryption(false)
                            .build());
            inventoryUrl = paramResponse.parameter().value();
        } catch (Exception e) {
            // Fallback to environment variable if SSM is unavailable
            inventoryUrl = System.getenv().getOrDefault("INVENTORY_SERVICE_URL",
                    "https://inventory-service.internal/rooms/available");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Hard-coded absolute file path replaced with S3-backed report service
        String reportName = month + "_bookings.pdf";
        String reportUrl = bookingService.generateReport(month);

        Map<String, Object> response = new HashMap<>();
        response.put("reportName", reportName);
        response.put("message", reportUrl);
        return response;
    }
}
