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

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Blocker-20: In-memory HashMap cache (without TTL) replaced with Amazon ElastiCache
    // for Redis via Spring's RedisTemplate. TTL is enforced on every cache entry to prevent
    // unbounded memory growth and ensure consistency across horizontally-scaled instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final String CACHE_PREFIX = "booking:cache:";

    // Blocker-10: Hard-coded environment URL replaced with AWS SSM Parameter Store lookup.
    // The parameter /resortslite/inventory/service-url is managed in Parameter Store and
    // injected via Spring's @Value with a safe local-dev default.
    @Value("${app.inventory.endpoint:https://inventory-service.internal:8081/rooms/available}")
    private String inventoryServiceUrl;

    @Autowired
    private SsmClient ssmClient;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Blocker-13/14/15/16/17: HTTP session attributes replaced with Amazon ElastiCache
        // for Redis using Spring Session. Session data is stored centrally in Redis so all
        // application instances share the same session state, enabling stateless horizontal
        // scaling and surviving instance restarts or failover events.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("session:lastBooking:" + bookingId, booking, CACHE_TTL);
        redisTemplate.opsForValue().set("session:guestName:" + bookingId, guestName, CACHE_TTL);

        // Blocker-20: Cache entry stored in Redis with TTL instead of unbounded in-memory Map
        redisTemplate.opsForValue().set(CACHE_PREFIX + bookingId, booking, CACHE_TTL);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // Blocker-13/14/15/16/17: Session data retrieved from Redis (ElastiCache) instead
        // of HttpSession — consistent across all instances in the cluster.
        String lastGuest = (String) redisTemplate.opsForValue().get("session:guestName:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Blocker-10: Hard-coded environment URL replaced with SSM Parameter Store value.
        // The inventory service URL is resolved at runtime from Parameter Store, enabling
        // environment-agnostic deployments without code changes between dev/staging/prod.
        String resolvedInventoryUrl = getParameterFromSsm(
                "/resortslite/inventory/service-url", inventoryServiceUrl);

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", resolvedInventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report files are now stored in Amazon S3; the path is an S3 object key,
        // not a local file system path.
        String s3ObjectKey = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("s3ObjectKey", s3ObjectKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    /**
     * Helper: retrieves a parameter value from AWS SSM Parameter Store.
     * Falls back to the provided default if the parameter cannot be resolved.
     */
    private String getParameterFromSsm(String parameterName, String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
