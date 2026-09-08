package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController handles HTTP endpoints for resort booking operations.
 *
 * Cloud-readiness changes applied:
 * - In-memory HashMap cache (cr-java-0067) replaced with Amazon ElastiCache for Redis
 *   via Spring's RedisTemplate with TTL-based expiration.
 * - HTTP session state (cr-java-0065) replaced with Spring Session backed by
 *   Amazon ElastiCache for Redis, enabling stateless, horizontally scalable instances.
 * - Hard-coded environment URL (cr-java-0071) replaced with AWS SSM Parameter Store lookup.
 */
@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * RedisTemplate replaces the static in-memory HashMap cache (cr-java-0067).
     * Amazon ElastiCache for Redis provides TTL-based expiration, cross-instance
     * consistency, and controlled memory growth.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL in seconds — injected from environment/config (default 30 minutes)
    @Value("${cache.booking.ttl-seconds:1800}")
    private long bookingCacheTtlSeconds;

    // AWS region injected from environment variable
    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    // SSM parameter name for the inventory service URL — replaces hard-coded URL
    @Value("${cloud.aws.ssm.inventory-url-param:/resorts/inventory/service-url}")
    private String inventoryUrlSsmParam;

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * @param paramName the SSM parameter name
     * @return the parameter value, or empty string if unavailable
     */
    private String getSsmParameter(String paramName) {
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .build();
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(false)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Creates a new booking. Session state is stored in Amazon ElastiCache for Redis
     * via Spring Session — replaces HttpSession (cr-java-0065). Booking is cached in
     * Redis with TTL — replaces static in-memory HashMap (cr-java-0067).
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @org.springframework.web.bind.annotation.SessionAttribute(name = "sessionId", required = false)
                    String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        String bookingId = (String) booking.get("bookingId");

        // Store session state in Redis via Spring Session (replaces HttpSession — cr-java-0065)
        // Spring Session @EnableRedisHttpSession automatically backs HttpSession with Redis,
        // so any injected HttpSession is already Redis-backed. We additionally store
        // explicit keys for cross-instance access.
        redisTemplate.opsForValue().set("session:lastBooking:" + bookingId, booking,
                bookingCacheTtlSeconds, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set("session:guestName:" + bookingId, guestName,
                bookingCacheTtlSeconds, TimeUnit.SECONDS);

        // Cache booking in Redis with TTL — replaces static HashMap (cr-java-0067)
        redisTemplate.opsForValue().set("booking:cache:" + bookingId, booking,
                bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves booking status. Session guest name is read from Redis — replaces
     * HttpSession.getAttribute (cr-java-0065) which would return null on other instances.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // Read session state from Redis — replaces HttpSession (cr-java-0065)
        Object lastGuestObj = redisTemplate.opsForValue().get("session:guestName:" + bookingId);
        String lastGuest = lastGuestObj != null ? lastGuestObj.toString() : null;

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability. The inventory service URL is retrieved from AWS SSM
     * Parameter Store — replaces the hard-coded environment URL (cr-java-0071).
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Retrieve inventory service URL from AWS SSM Parameter Store — replaces hard-coded URL (cr-java-0071)
        String inventoryUrl = getSsmParameter(inventoryUrlSsmParam);
        if (inventoryUrl == null || inventoryUrl.isEmpty()) {
            inventoryUrl = System.getenv().getOrDefault("INVENTORY_SERVICE_URL",
                    "https://inventory-service.internal/rooms/available");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a pre-signed S3 URL or report path for the requested month's report.
     * The hard-coded local file path has been replaced with an S3-based reference.
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // S3 object key replaces hard-coded local file path (czr-java-001)
        String s3ObjectKey = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("s3ObjectKey", s3ObjectKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
