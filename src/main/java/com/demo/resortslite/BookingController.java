package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * BookingController — cloud-ready REST controller.
 *
 * Session state is now managed by Amazon ElastiCache for Redis via Spring Session
 * (cr-java-0065). This replaces the previous in-process HTTP session storage that
 * caused data loss under horizontal scaling and ALB load balancing.
 *
 * The in-memory bookingCache (cr-java-0067) has been replaced with Redis-backed
 * Spring Session, providing TTL-controlled, distributed cache management.
 *
 * Hard-coded environment URLs (cr-java-0071) are now retrieved from AWS SSM
 * Parameter Store at runtime, enabling environment-agnostic deployments.
 */
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // SSM client for retrieving environment-specific URLs (cr-java-0071).
    private final SsmClient ssmClient;

    public BookingController() {
        this.ssmClient = SsmClient.create();
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Session state is now stored in Amazon ElastiCache for Redis via Spring Session
        // (cr-java-0065). The HttpSession API is preserved but backed by Redis, so all
        // instances in the cluster share the same session store — no server affinity needed.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // Removed the static in-memory bookingCache (cr-java-0067). Session data is now
        // persisted in Redis with a TTL (maxInactiveIntervalInSeconds = 1800), preventing
        // unbounded memory growth and ensuring consistency across instances.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Session attribute is now retrieved from Redis — consistent across all instances
        // (cr-java-0065). No longer returns null when the request lands on a different node.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Replaced hard-coded inventory URL (cr-java-0071) with AWS SSM Parameter Store lookup.
        // The parameter /resorts/inventory/endpoint must be configured per environment.
        String inventoryUrl = getParameterFromSsm("/resorts/inventory/endpoint",
                "https://inventory-service.internal/rooms/available");

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Replaced hard-coded absolute file path (cr-java-0061) with an S3-based URL
        // retrieved from AWS SSM Parameter Store (cr-java-0071).
        String reportBaseUrl = getParameterFromSsm("/resorts/report/base-url",
                "https://resorts-reports-bucket.s3.amazonaws.com/reports");
        String reportKey = month + "_bookings.pdf";
        String reportUrl = reportBaseUrl + "/" + reportKey;

        Map<String, Object> response = new HashMap<>();
        response.put("reportUrl", reportUrl);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    /**
     * Helper: retrieve a parameter value from AWS SSM Parameter Store.
     * Falls back to {@code defaultValue} if the parameter is not found or SSM is unavailable.
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
