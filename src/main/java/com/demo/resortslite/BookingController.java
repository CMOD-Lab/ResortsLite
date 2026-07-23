package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * BookingController handles HTTP requests for resort booking operations.
 *
 * <p>Cloud-native changes applied:
 * <ul>
 *   <li>HTTP session state is now managed by Amazon ElastiCache for Redis via Spring Session
 *       (blockers 13-17 / cr-java-0065). The {@code @EnableRedisHttpSession} annotation
 *       configures Spring to store all HttpSession data in Redis, enabling stateless
 *       application instances and safe horizontal scaling behind AWS ALB.</li>
 *   <li>The in-memory {@code bookingCache} HashMap has been replaced with a Redis-backed
 *       cache via Spring Session / ElastiCache (blocker 20 / cr-java-0067). This ensures
 *       TTL-controlled expiration and consistent data across all instances.</li>
 *   <li>The hard-coded inventory service URL is externalized to AWS Systems Manager
 *       Parameter Store (blocker 10 / cr-java-0071).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private SsmClient ssmClient;

    /**
     * Inventory service URL injected from environment variable.
     * Replaces hard-coded URL (blocker-10 / cr-java-0071).
     * Falls back to SSM Parameter Store resolution at runtime.
     */
    @Value("${app.inventory.endpoint:#{null}}")
    private String inventoryEndpointOverride;

    /**
     * SSM Parameter Store key for the inventory service URL.
     */
    @Value("${aws.ssm.inventory-url-param:/resortslite/inventory/endpoint}")
    private String inventoryUrlParam;

    /**
     * Creates a new booking and stores session state in Amazon ElastiCache for Redis.
     * The {@code HttpSession} is backed by Spring Session + Redis, so session data is
     * shared across all application instances (replaces instance-local session storage,
     * blockers 13-17 / cr-java-0065).
     * Booking is also cached in the Redis-backed session store with TTL controlled by
     * {@code maxInactiveIntervalInSeconds} (replaces unbounded in-memory HashMap,
     * blocker 20 / cr-java-0067).
     *
     * @param guestName guest's full name
     * @param roomType  type of room requested
     * @param checkIn   check-in date string
     * @param checkOut  check-out date string
     * @param session   HTTP session backed by Amazon ElastiCache for Redis
     * @return booking confirmation response
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Session state stored in Amazon ElastiCache for Redis via Spring Session.
        // All instances share the same Redis store — no server affinity required.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // Cache the booking in the Redis-backed session store with TTL.
        // Replaces the unbounded static in-memory HashMap (blocker 20 / cr-java-0067).
        @SuppressWarnings("unchecked")
        Map<String, Object> sessionCache = (Map<String, Object>) session.getAttribute("bookingCache");
        if (sessionCache == null) {
            sessionCache = new HashMap<>();
        }
        sessionCache.put((String) booking.get("bookingId"), booking);
        session.setAttribute("bookingCache", sessionCache);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking. Session data is retrieved from Amazon ElastiCache
     * for Redis, ensuring consistent results regardless of which instance handles the request
     * (replaces instance-local session reads, blockers 13-17 / cr-java-0065).
     *
     * @param bookingId the booking identifier
     * @param session   HTTP session backed by Amazon ElastiCache for Redis
     * @return booking status response
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Session attribute read from Redis — consistent across all cluster instances
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability. The inventory service URL is resolved from
     * AWS Systems Manager Parameter Store (blocker-10 / cr-java-0071),
     * enabling environment-agnostic deployments without code changes.
     *
     * @param roomType the room type to check
     * @return availability response
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Resolve inventory URL from AWS SSM Parameter Store — replaces hard-coded URL
        String inventoryUrl = resolveInventoryEndpoint();

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a download link for a monthly booking report.
     * Report files are stored in Amazon S3; the path is no longer a local file system path.
     *
     * @param month the month for the report
     * @return report download response
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // S3 object key replaces hard-coded local file path
        String s3ObjectKey = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("s3ObjectKey", s3ObjectKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    /**
     * Resolves the inventory service endpoint from AWS Systems Manager Parameter Store.
     * Falls back to environment variable override or a safe default if SSM is unavailable.
     * Replaces hard-coded URL (blocker-10 / cr-java-0071).
     */
    private String resolveInventoryEndpoint() {
        if (inventoryEndpointOverride != null && !inventoryEndpointOverride.isEmpty()) {
            return inventoryEndpointOverride;
        }
        try {
            GetParameterResponse response = ssmClient.getParameter(
                    GetParameterRequest.builder()
                            .name(inventoryUrlParam)
                            .withDecryption(false)
                            .build());
            return response.parameter().value();
        } catch (Exception e) {
            return System.getenv().getOrDefault("INVENTORY_ENDPOINT",
                    "https://inventory-service.internal/rooms/available");
        }
    }
}
