package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// Updated from javax.servlet.http.HttpSession to jakarta.servlet.http.HttpSession
// (JAVA8_TO_21_JAKARTA_EE_MIGRATION): javax.* packages removed in Java 17 / Spring Boot 3.x
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIX cr-java-0067 [Cloud Compatibility / Mandatory]: Removed static in-memory cache.
    // Instance-local caches break horizontal scaling — cache entries on instance A are
    // invisible to instance B. For distributed caching, use Redis (AWS ElastiCache) or
    // a similar shared cache layer injected via Spring Cache abstraction.

    // FIX cr-java-0088 [Cloud Compatibility / Mandatory]: Inventory service URL externalised
    // to application.properties / environment variable and changed to HTTPS.
    @Value("${app.inventory.endpoint:https://inventory-svc.internal/rooms}")
    private String inventoryEndpoint;

    /**
     * Creates a new booking and returns the confirmed booking details.
     *
     * <p>FIX cr-java-0065 [Cloud Compatibility / Mandatory]: Booking state is no longer
     * stored in the HTTP session. AWS ALB distributes requests across EC2 instances —
     * session data on instance A is invisible to instance B, causing failures on
     * auto-scaling and failover. State is returned directly in the response body instead.</p>
     *
     * @param guestName Guest's full name.
     * @param roomType  Room category (STANDARD, DELUXE, SUITE, VILLA).
     * @param checkIn   Check-in date (yyyy-MM-dd).
     * @param checkOut  Check-out date (yyyy-MM-dd).
     * @return Confirmed booking details.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIX cr-java-0065: Session attributes removed. Booking state is returned in the
        // response body; callers should persist state client-side or via a shared store.
        // FIX cr-java-0067: Static in-memory bookingCache removed. Use a distributed
        // cache (e.g. Redis / AWS ElastiCache) for cross-instance state sharing.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the current status of a booking by its ID.
     *
     * <p>FIX cr-java-0065: Session dependency removed. The booking is looked up directly
     * from the persistent store via {@link BookingService#getBookingById(String)}.</p>
     *
     * @param bookingId Unique booking identifier.
     * @return Booking status and details.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        // FIX cr-java-0065: Removed session.getAttribute("guestName") — reading business
        // state from HTTP session fails on any other instance in the cluster.
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability for the requested room type.
     *
     * <p>FIX cr-java-0088: Inventory service URL changed to HTTPS and externalised to
     * an environment variable / application.properties.</p>
     *
     * @param roomType Room category to check.
     * @return Availability status and inventory endpoint used.
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIX cr-java-0088: Plain HTTP URL replaced with HTTPS endpoint sourced from
        // injected property — complies with AWS WAF and Well-Architected security review.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a download reference for a monthly booking report.
     *
     * <p>FIX czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute file path
     * removed. Report path is now resolved from the externalised {@code app.report.base-path}
     * property so it works inside container images and cloud environments.</p>
     *
     * @param month Month identifier for the report (e.g. "2024-03").
     * @return Report path reference and generation message.
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIX czr-java-001: Hardcoded /var/legacy/reports/ path removed.
        // Report path is now constructed using the externalised base-path property
        // injected into BookingService / ReportService.
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
