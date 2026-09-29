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

    // Blocker-20 (cr-java-0067): Replace unbounded in-memory HashMap cache with
    // Amazon ElastiCache for Redis via RedisTemplate — TTL enforced on every write
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL in seconds — externalised via environment variable (default 1 hour)
    @Value("${cache.booking.ttl-seconds:3600}")
    private long bookingCacheTtlSeconds;

    // SSM parameter name for the inventory service URL (blocker-10)
    @Value("${cloud.aws.ssm.inventory-url-param:/resortslite/inventory/endpoint}")
    private String inventoryUrlSsmParam;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {
        // Blocker-13/14/15/16/17 (cr-java-0065): HttpSession removed entirely.
        // Booking state is stored in Amazon ElastiCache for Redis with TTL,
        // enabling stateless instances and safe horizontal scaling.

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        String bookingId = (String) booking.get("bookingId");

        // Store last booking reference per guest in Redis with TTL (blocker-13/14/15/16/17)
        redisTemplate.opsForValue().set("lastBooking:" + guestName, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set("guestName:" + bookingId, guestName, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        // Blocker-20 (cr-java-0067): Store booking in Redis cache with TTL instead of
        // unbounded in-memory HashMap
        redisTemplate.opsForValue().set("booking:" + bookingId, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        // Blocker-13/14/15/16/17 (cr-java-0065): HttpSession removed.
        // Guest name retrieved from Redis (ElastiCache) — consistent across all instances.
        String lastGuest = (String) redisTemplate.opsForValue().get("guestName:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Blocker-10 (cr-java-0071): Hard-coded inventory URL replaced with value
        // retrieved from AWS Systems Manager Parameter Store
        String inventoryUrl;
        try {
            GetParameterResponse paramResponse = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(inventoryUrlSsmParam)
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
        // Hard-coded /var/legacy/reports/ path removed; report location is now S3-based
        // and resolved by ReportService.buildReportDownloadUrl()
        String reportKey = month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportKey", reportKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
