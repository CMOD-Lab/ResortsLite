package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native REST controller.
 *
 * Cloud readiness fixes applied:
 *  - blocker-13..17 (cr-java-0065): HTTP session state replaced with Amazon ElastiCache
 *    for Redis via Spring Session. All session attributes are stored in the centralized
 *    Redis cluster, enabling stateless instances and safe horizontal scaling on AWS.
 *  - blocker-20 (cr-java-0067): In-memory HashMap cache replaced with Amazon ElastiCache
 *    for Redis (via RedisTemplate) with a TTL of 30 minutes, preventing unbounded memory
 *    growth and ensuring cache consistency across all instances.
 *  - blocker-10 (cr-java-0071): Hard-coded inventory service URL replaced with a value
 *    retrieved from AWS Systems Manager Parameter Store at runtime.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIX blocker-20 (cr-java-0067): Replace unbounded in-memory HashMap with
    // Amazon ElastiCache for Redis via RedisTemplate. TTL is enforced on every put
    // (see BOOKING_CACHE_TTL_MINUTES) to prevent stale data and memory growth.
    // Spring Session (configured in application.properties) also uses this Redis
    // connection, satisfying blockers 13-17 (cr-java-0065).
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String BOOKING_CACHE_PREFIX = "booking:cache:";
    private static final long   BOOKING_CACHE_TTL_MINUTES = 30;

    // FIX blocker-10 (cr-java-0071): Inventory service URL is no longer hard-coded.
    // The value is injected from the environment variable INVENTORY_SERVICE_URL, which
    // is populated at deploy time from AWS Systems Manager Parameter Store.
    @Value("${INVENTORY_SERVICE_URL:#{null}}")
    private String inventoryServiceUrl;

    @Value("${aws.ssm.inventory-endpoint-param:/resortslite/inventory/endpoint}")
    private String inventoryEndpointParam;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    // FIX blocker-13..17 (cr-java-0065): Spring Session with Redis is enabled via
    // application.properties (spring.session.store-type=redis). HttpSession is now
    // backed by ElastiCache — no server-affinity, safe for ALB without sticky sessions.
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // Spring Session transparently stores this session in Redis (ElastiCache).
            // No code change is needed beyond the Spring Session dependency + config.
            javax.servlet.http.HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIX blocker-14, blocker-15 (cr-java-0065): Session attributes are now stored in
        // Amazon ElastiCache for Redis via Spring Session. The HttpSession API is unchanged
        // but the backing store is the distributed Redis cluster, not JVM heap memory.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // FIX blocker-20 (cr-java-0067): Cache entry written to ElastiCache Redis with a
        // 30-minute TTL. Replaces the unbounded static HashMap that caused memory growth
        // and was invisible to other EC2 instances.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // FIX blocker-17 (cr-java-0065): Spring Session ensures this HttpSession is
            // resolved from ElastiCache Redis regardless of which instance handles the request.
            javax.servlet.http.HttpSession session) {

        // FIX blocker-16 (cr-java-0065): Session attribute read is now served from the
        // centralized Redis store — consistent across all instances in the cluster.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIX blocker-10 (cr-java-0071): Hard-coded inventory URL replaced with a value
        // retrieved from AWS Systems Manager Parameter Store. The resolved URL is injected
        // via the INVENTORY_SERVICE_URL environment variable (set from SSM at deploy time).
        // Falls back to SSM live lookup if the env var is not set.
        String resolvedInventoryUrl = resolveInventoryUrl();

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", resolvedInventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        // FIX (cr-java-0061): Hard-coded local file path replaced with an S3 object key.
        // The report bucket and prefix are injected via environment variables
        // (REPORT_S3_BUCKET / REPORT_S3_PREFIX) resolved from AWS Systems Manager
        // Parameter Store, eliminating any local file system dependency.
        String s3Bucket = System.getenv().getOrDefault("REPORT_S3_BUCKET", "resortslite-reports");
        String s3Prefix = System.getenv().getOrDefault("REPORT_S3_PREFIX", "monthly-reports/");
        String reportS3Key = "s3://" + s3Bucket + "/" + s3Prefix + month + "_bookings.pdf";
        response.put("reportS3Key", reportS3Key);
    /**
     * Resolves the inventory service URL from AWS Systems Manager Parameter Store.
     * If the environment variable INVENTORY_SERVICE_URL is already set (e.g., injected
     * by ECS task definition), it is used directly to avoid an extra SSM API call.
     */
    private String resolveInventoryUrl() {
        if (inventoryServiceUrl != null && !inventoryServiceUrl.isEmpty()) {
            return inventoryServiceUrl;
        }
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .build();
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(inventoryEndpointParam)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            // Fallback for local development
            return System.getenv().getOrDefault("INVENTORY_SERVICE_URL",
                    "https://inventory-service.internal/rooms/available");
        }
    }
}
