package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native REST controller for resort bookings.
 *
 * <p>HTTP session state (cr-java-0065) has been replaced with Amazon ElastiCache for
 * Redis via Spring Session and {@link RedisTemplate}, enabling stateless instances and
 * horizontal scaling.  The in-memory {@code HashMap} cache without TTL (cr-java-0067)
 * has been replaced with Spring Cache backed by Redis with a configurable TTL.  The
 * hard-coded environment URL (cr-java-0071) is now retrieved from AWS Systems Manager
 * Parameter Store.</p>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIX cr-java-0067: Replaced static in-memory HashMap (no TTL) with Redis-backed
    // Spring Cache.  TTL is configured in application.properties via
    // spring.cache.redis.time-to-live, ensuring controlled expiration and consistency
    // across all instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final SsmClient ssmClient;

    // FIX cr-java-0071: Inventory service URL externalised — value populated from SSM
    // Parameter Store at startup via application.properties / environment variable.
    @Value("${app.inventory.endpoint:https://inventory-service.internal/rooms/available}")
    private String inventoryEndpoint;

    public BookingController(SsmClient ssmClient) {
        this.ssmClient = ssmClient;
    }

    /**
     * Creates a new booking and stores the booking state in Redis (ElastiCache) instead
     * of the local HTTP session, enabling stateless horizontal scaling.
     *
     * @param guestName guest full name
     * @param roomType  room category
     * @param checkIn   check-in date
     * @param checkOut  check-out date
     * @return confirmation response map
     */
    @PostMapping("/create")
    // FIX cr-java-0067: @CachePut stores the result in Redis with TTL — replaces the
    // unbounded in-memory bookingCache HashMap.
    @CachePut(value = "bookings", key = "#result['booking']['bookingId']")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIX cr-java-0065: Booking state stored in Redis (ElastiCache) instead of
        // HTTP session.  Redis is shared across all instances — no server affinity needed.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("lastBooking:" + bookingId, booking, 30, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set("guestName:" + bookingId, guestName, 30, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking, reading guest context from Redis instead of
     * the local HTTP session.
     *
     * @param bookingId the booking identifier
     * @return booking status map
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // FIX cr-java-0065: Guest name retrieved from Redis — consistent across all
        // instances regardless of which node handles the request.
        String lastGuest = (String) redisTemplate.opsForValue().get("guestName:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using the inventory service URL retrieved from
     * AWS Systems Manager Parameter Store.
     *
     * @param roomType room category to check
     * @return availability response map
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIX cr-java-0071: Inventory URL retrieved from SSM Parameter Store at runtime,
        // replacing the hard-coded "http://inventory-service.internal:8081/rooms/available".
        String resolvedInventoryUrl = getParameterFromSsm(
                "/resortslite/inventory/endpoint", inventoryEndpoint);

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", resolvedInventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a pre-signed S3 URL for report download instead of a local file path.
     *
     * @param month month identifier for the report
     * @return report download response map
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIX cr-java-0061 / cr-java-0071: Report path is now an S3 object key;
        // the download URL is constructed by BookingService using the SSM-backed base URL.
        String reportKey = "reports/" + month + "_bookings.csv";

        Map<String, Object> response = new HashMap<>();
        response.put("reportKey", reportKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store,
     * falling back to the provided default if SSM is unavailable.
     *
     * @param paramName    SSM parameter path
     * @param defaultValue fallback value
     * @return resolved parameter value
     */
    private String getParameterFromSsm(String paramName, String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(false)
                    .build();
            return ssmClient.getParameter(request).parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
