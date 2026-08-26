package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// Updated from javax.servlet.http.HttpSession to jakarta.servlet.http.HttpSession
// Spring Boot 3.x / Java 17 uses Jakarta EE 10 (jakarta.*) instead of javax.*
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Fixed cr-java-0067 [Cloud Compatibility / Mandatory]: Removed instance-local
    // in-memory cache. In a horizontally scaled deployment (ECS/EKS) each instance
    // has its own heap — a local HashMap is invisible to other instances and breaks
    // consistency. Caching should be delegated to a distributed store (e.g. Redis /
    // ElastiCache) when required. The cache field has been removed entirely here;
    // add a distributed cache layer (Spring Cache + Redis) if caching is needed.

    // Fixed cr-java-0088 [Cloud Compatibility / Mandatory]: Hardcoded plain-HTTP
    // inventory endpoint replaced with an environment-variable-backed property.
    @Value("${app.inventory.endpoint:https://inventory-svc/rooms}")
    private String inventoryEndpoint;

    // Fixed czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute report
    // path replaced with an environment-variable-backed property.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    /**
     * Creates a new booking.
     *
     * <p>Fixed cr-java-0065 [Cloud Compatibility / Mandatory]: Booking state is no longer
     * stored in the HTTP session. AWS ALB distributes requests across instances — session
     * data stored on instance A is invisible to instance B, causing failures on auto-scaling
     * and failover. The booking details are returned directly in the response body; callers
     * should persist state in a shared store (database, Redis) if cross-request state is
     * needed.</p>
     *
     * @param guestName Guest's full name
     * @param roomType  Room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn   Check-in date (yyyy-MM-dd)
     * @param checkOut  Check-out date (yyyy-MM-dd)
     * @return Confirmation response containing booking details
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Fixed cr-java-0065: Removed session.setAttribute calls.
        // Booking state is returned in the response; persistent state must be stored
        // in the database (already done via BookingService.createBooking) or a
        // distributed cache — not in instance-local HTTP session memory.

        // Fixed cr-java-0067: Removed bookingCache.put — instance-local cache removed.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the current status of a booking by its identifier.
     *
     * <p>Fixed cr-java-0065 [Cloud Compatibility / Mandatory]: Removed HTTP session
     * read. Guest context is now retrieved from the database via BookingService,
     * ensuring consistent results regardless of which cluster instance handles the
     * request.</p>
     *
     * @param bookingId Unique booking identifier
     * @return Map containing booking details
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // Fixed cr-java-0065: Removed session.getAttribute("guestName").
        // Booking details (including guest name) are fetched from the database.
        Map<String, Object> details = bookingService.getBookingById(bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", details);
        return result;
    }

    /**
     * Checks room availability for the requested room type.
     *
     * <p>Fixed cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP inventory
     * endpoint replaced with an HTTPS URL supplied via the {@code app.inventory.endpoint}
     * environment variable (fixes cr-java-0021 as well).</p>
     *
     * @param roomType Room category to check
     * @return Map containing availability status and the resolved inventory endpoint
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Fixed cr-java-0088 / cr-java-0021: inventoryEndpoint is now injected from
        // an environment variable (HTTPS by default) instead of being hardcoded as
        // a plain-HTTP string literal.

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns the download path for a monthly booking report.
     *
     * <p>Fixed czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
     * path {@code /var/legacy/reports/} replaced with the {@code app.report.base-path}
     * property, which is supplied via environment variable and defaults to
     * {@code /tmp/reports/} for container compatibility.</p>
     *
     * @param month Month identifier for the report
     * @return Map containing the resolved report path and generation message
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Fixed czr-java-001: reportBasePath is injected from environment variable
        // instead of being hardcoded as /var/legacy/reports/.
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
