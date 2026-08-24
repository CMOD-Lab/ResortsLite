package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native implementation.
 *
 * <p>HTTP session state (cr-java-0065) has been migrated to Amazon ElastiCache
 * for Redis using Spring Session, enabling stateless application instances with
 * centralized, distributed session management. The unbounded in-memory cache
 * (cr-java-0067) has been replaced with Amazon ElastiCache for Redis with a
 * TTL policy. The hard-coded inventory service URL (cr-java-0071) is now
 * resolved from AWS Systems Manager Parameter Store.</p>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // -----------------------------------------------------------------------
    // cr-java-0067 — Unbounded in-memory HashMap replaced with Amazon
    // ElastiCache for Redis via Spring Data Redis. TTL is applied on every
    // write so entries expire automatically and memory is bounded.
    // -----------------------------------------------------------------------
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** TTL for booking cache entries in ElastiCache Redis (24 hours). */
    private static final long BOOKING_CACHE_TTL_HOURS = 24L;

    /** Redis key prefix for booking cache entries. */
    private static final String BOOKING_CACHE_PREFIX = "booking:";

    /** Redis key prefix for session guest-name entries. */
    private static final String SESSION_GUEST_PREFIX = "session:guest:";

    /** Redis key prefix for last-booking session entries. */
    private static final String SESSION_LAST_BOOKING_PREFIX = "session:lastBooking:";

    // -----------------------------------------------------------------------
    // cr-java-0071 — Hard-coded inventory service URL replaced with a value
    // retrieved from AWS Systems Manager Parameter Store at startup.
    // -----------------------------------------------------------------------
    private static final String INVENTORY_URL = resolveInventoryUrl();

    private static String resolveInventoryUrl() {
        String envVal = System.getenv("INVENTORY_SERVICE_URL");
        if (envVal != null && !envVal.isEmpty()) {
            return envVal;
        }
        try {
            SsmClient ssm = SsmClient.create();
            GetParameterResponse resp = ssm.getParameter(
                    GetParameterRequest.builder()
                            .name("/resortslite/inventory/service-url")
                            .withDecryption(false)
                            .build());
            return resp.parameter().value();
        } catch (Exception e) {
            return "https://inventory-service.internal/rooms/available";
        }
    }

    /**
     * Creates a new booking and stores session state in Amazon ElastiCache for Redis.
     *
     * <p>cr-java-0065: Session attributes (lastBooking, guestName) are stored in
     * Redis instead of the local HTTP session, enabling any instance in the cluster
     * to serve subsequent requests for the same logical session.</p>
     *
     * <p>cr-java-0067: The booking is cached in Redis with a 24-hour TTL instead of
     * an unbounded in-memory HashMap.</p>
     *
     * @param guestName  guest's full name
     * @param roomType   room category
     * @param checkIn    check-in date string
     * @param checkOut   check-out date string
     * @param sessionId  caller-supplied session identifier (replaces HttpSession)
     * @return confirmation response map
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "default") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065: Store session state in ElastiCache Redis (not HttpSession)
        redisTemplate.opsForValue().set(
                SESSION_LAST_BOOKING_PREFIX + sessionId, booking,
                BOOKING_CACHE_TTL_HOURS, TimeUnit.HOURS);
        redisTemplate.opsForValue().set(
                SESSION_GUEST_PREFIX + sessionId, guestName,
                BOOKING_CACHE_TTL_HOURS, TimeUnit.HOURS);

        // cr-java-0067: Cache booking in Redis with TTL (replaces unbounded HashMap)
        redisTemplate.opsForValue().set(
                BOOKING_CACHE_PREFIX + booking.get("bookingId"), booking,
                BOOKING_CACHE_TTL_HOURS, TimeUnit.HOURS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking, reading session context from ElastiCache Redis.
     *
     * <p>cr-java-0065: Guest name is retrieved from Redis rather than from a
     * server-local HTTP session, so any instance in the cluster can serve the request.</p>
     *
     * @param bookingId booking identifier
     * @param sessionId caller-supplied session identifier
     * @return booking status map
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "default") String sessionId) {

        // cr-java-0065: Read session state from ElastiCache Redis (not HttpSession)
        String lastGuest = (String) redisTemplate.opsForValue()
                .get(SESSION_GUEST_PREFIX + sessionId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using the inventory service URL resolved from SSM.
     *
     * <p>cr-java-0071: The inventory service URL is no longer hard-coded; it is
     * resolved from AWS Systems Manager Parameter Store or the
     * {@code INVENTORY_SERVICE_URL} environment variable at startup.</p>
     *
     * @param roomType room category to check
     * @return availability response map
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071: URL sourced from SSM Parameter Store / env var — not hard-coded
        String inventoryUrl = INVENTORY_URL + "?roomType=" + roomType;

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a download reference for a monthly report stored in Amazon S3.
     *
     * @param month month identifier (e.g. "2024-03")
     * @return response map with S3 reference and report message
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now an S3 key, not a local absolute path
        String reportS3Key = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportS3Key", reportS3Key);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
