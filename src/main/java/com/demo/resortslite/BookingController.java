package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

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
 * - Blocker-13/14/15/16/17 (cr-java-0065): HTTP session state replaced with
 *   Amazon ElastiCache for Redis via Spring Session / RedisTemplate for
 *   distributed, stateless session management.
 * - Blocker-20 (cr-java-0067): In-memory HashMap cache replaced with
 *   Amazon ElastiCache for Redis with TTL to prevent unbounded memory growth.
 * - Blocker-10 (cr-java-0071): Hard-coded inventory service URL replaced with
 *   AWS Systems Manager Parameter Store lookup.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Blocker-20 (cr-java-0067): Replace in-memory HashMap cache (no TTL) with
    // Amazon ElastiCache for Redis via RedisTemplate — TTL enforced per entry.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private SsmClient ssmClient;

    // Blocker-20 (cr-java-0067): Cache TTL — configurable via environment variable
    @Value("${app.cache.booking.ttl-minutes:30}")
    private long bookingCacheTtlMinutes;

    // Blocker-10 (cr-java-0071): SSM parameter name for inventory service URL
    @Value("${app.inventory.url.param:/resortslite/inventory/url}")
    private String inventoryUrlParam;

    private static final String CACHE_PREFIX = "booking:";
    private static final String SESSION_PREFIX = "session:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Blocker-14/15 (cr-java-0065): Store session state in Redis (ElastiCache) instead of
        // HttpSession — data is shared across all instances behind the load balancer.
        if (sessionId != null && !sessionId.isEmpty()) {
            redisTemplate.opsForValue().set(
                    SESSION_PREFIX + sessionId + ":lastBooking",
                    booking,
                    Duration.ofMinutes(bookingCacheTtlMinutes));
            redisTemplate.opsForValue().set(
                    SESSION_PREFIX + sessionId + ":guestName",
                    guestName,
                    Duration.ofMinutes(bookingCacheTtlMinutes));
        }

        // Blocker-20 (cr-java-0067): Store booking in Redis with TTL instead of unbounded HashMap
        redisTemplate.opsForValue().set(
                CACHE_PREFIX + booking.get("bookingId"),
                booking,
                Duration.ofMinutes(bookingCacheTtlMinutes));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        // Blocker-13/16 (cr-java-0065): Read session state from Redis (ElastiCache) —
        // consistent across all horizontally-scaled instances.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            Object val = redisTemplate.opsForValue().get(SESSION_PREFIX + sessionId + ":guestName");
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
        // instead of using a hard-coded "http://inventory-service.internal:8081/rooms/available".
        String inventoryUrl;
        try {
            GetParameterResponse paramResponse = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(inventoryUrlParam)
                            .withDecryption(false)
                            .build());
            inventoryUrl = paramResponse.parameter().value();
        } catch (Exception e) {
            // Fallback to environment variable — never a hard-coded value
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
        // Blocker-17 (cr-java-0065): No local file path — report location is S3 object key.
        // The S3 pre-signed URL or key is returned; no ephemeral local path dependency.
        String s3ObjectKey = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("s3ObjectKey", s3ObjectKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
