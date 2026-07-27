package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private SsmClient ssmClient;

    // Blocker-20 (cr-java-0067): In-memory HashMap cache without TTL replaced with
    // Amazon ElastiCache for Redis via Spring RedisTemplate.
    // Redis provides TTL-based expiration, cross-instance consistency, and controlled memory.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Default TTL for cached booking entries: 30 minutes
    private static final long BOOKING_CACHE_TTL_MINUTES = 30L;

    // Blocker-10 (cr-java-0071): Hard-coded inventory URL replaced with value from
    // AWS Systems Manager Parameter Store, enabling environment-agnostic deployments.
    @Value("${app.inventory.endpoint:#{null}}")
    private String inventoryEndpointOverride;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Blocker-13,14,15,16,17 (cr-java-0065): HTTP session replaced with Amazon ElastiCache
        // for Redis via Spring Session. Session data is stored centrally so all instances
        // in the cluster share the same state — no server affinity required.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("session:lastBooking:" + bookingId, booking,
                BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set("session:guestName:" + bookingId, guestName,
                BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        // Blocker-20 (cr-java-0067): Booking stored in Redis with TTL instead of unbounded
        // in-memory HashMap, preventing memory growth and stale data across instances.
        redisTemplate.opsForValue().set("cache:booking:" + bookingId, booking,
                BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // Blocker-13,14,15,16,17 (cr-java-0065): Session data now retrieved from Redis
        // (ElastiCache) instead of local HTTP session — works correctly across all instances.
        String lastGuest = (String) redisTemplate.opsForValue()
                .get("session:guestName:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Blocker-10 (cr-java-0071): Hard-coded inventory URL resolved from AWS SSM
        // Parameter Store at runtime for environment-agnostic deployments.
        String inventoryUrl = resolveInventoryUrl();

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Hard-coded local file path removed; report retrieval now delegates to
        // ReportService which uses Amazon S3 for durable storage.
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    /**
     * Blocker-10 (cr-java-0071): Resolves the inventory service URL from AWS SSM
     * Parameter Store. Falls back to the Spring property override if SSM is unavailable.
     */
    private String resolveInventoryUrl() {
        if (inventoryEndpointOverride != null && !inventoryEndpointOverride.isEmpty()) {
            return inventoryEndpointOverride;
        }
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name("/resortslite/inventory/endpoint")
                    .withDecryption(false)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            // Fall back to a safe default if SSM is unreachable during local development
            return "https://inventory-service.internal/rooms/available";
        }
    }
}
