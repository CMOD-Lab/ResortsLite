package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * BookingController handles booking REST endpoints.
 *
 * Cloud-readiness changes applied:
 * - Blocker-13,14,15,16,17 (cr-java-0065): HTTP session state replaced with
 *   Amazon ElastiCache for Redis via Spring Session / RedisTemplate.
 * - Blocker-20 (cr-java-0067): In-memory HashMap cache replaced with
 *   Amazon ElastiCache for Redis with TTL-based expiration.
 * - Blocker-10 (cr-java-0071): Hard-coded inventory service URL replaced with
 *   AWS Systems Manager Parameter Store lookup.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Blocker-13,14,15,16,17 (cr-java-0065) & Blocker-20 (cr-java-0067):
    // RedisTemplate replaces both the in-memory HashMap cache and HttpSession state.
    // Spring Session automatically backs HttpSession with Redis when
    // spring-session-data-redis is on the classpath and @EnableRedisHttpSession is active.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Blocker-20 (cr-java-0067): TTL for booking cache entries — 30 minutes.
    private static final Duration BOOKING_CACHE_TTL = Duration.ofMinutes(30);

    // Blocker-13,14,15,16,17 (cr-java-0065): Session TTL for guest session data — 60 minutes.
    private static final Duration SESSION_TTL = Duration.ofMinutes(60);

    // Blocker-10 (cr-java-0071): SSM parameter name for inventory service URL.
    @Value("${ssm.parameter.inventory-url:/resortslite/inventory/endpoint}")
    private String inventoryUrlParam;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Blocker-14,15 (cr-java-0065): Store booking state and guest name in Redis
        // instead of HttpSession — visible to all instances behind the load balancer.
        String bookingId = (String) booking.get("bookingId");
        if (sessionId != null && !sessionId.isEmpty()) {
            redisTemplate.opsForValue().set(
                    "session:" + sessionId + ":lastBooking", booking, SESSION_TTL);
            redisTemplate.opsForValue().set(
                    "session:" + sessionId + ":guestName", guestName, SESSION_TTL);
        }

        // Blocker-20 (cr-java-0067): Store booking in Redis cache with TTL
        // instead of unbounded in-memory HashMap.
        redisTemplate.opsForValue().set(
                "booking:cache:" + bookingId, booking, BOOKING_CACHE_TTL);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // Blocker-16 (cr-java-0065): Read guest name from Redis instead of HttpSession —
        // consistent across all horizontally-scaled instances.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            Object val = redisTemplate.opsForValue().get("session:" + sessionId + ":guestName");
            lastGuest = val != null ? val.toString() : null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Blocker-10 (cr-java-0071): Retrieve inventory service URL from AWS SSM Parameter Store
        // instead of using a hard-coded environment-specific URL.
        String inventoryUrl = resolveInventoryUrl();

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Blocker-17 (cr-java-0065): No session state used here; report path is
        // now an S3 object key resolved by ReportService — no local file path.
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    /**
     * Resolves the inventory service URL from AWS Systems Manager Parameter Store.
     * Falls back to the environment variable INVENTORY_SERVICE_URL if SSM is unavailable.
     */
    private String resolveInventoryUrl() {
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
                    .build();
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(inventoryUrlParam)
                            .withDecryption(false)
                            .build());
            ssmClient.close();
            return response.parameter().value();
        } catch (Exception e) {
            return System.getenv().getOrDefault(
                    "INVENTORY_SERVICE_URL",
                    "https://inventory-service.internal/rooms/available");
        }
    }
}
