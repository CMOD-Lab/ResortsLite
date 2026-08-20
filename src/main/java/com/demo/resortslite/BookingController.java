package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * BookingController — REST API for resort booking operations.
 *
 * <p>All external service endpoints are externalised to application properties.
 * In-memory session state has been replaced with stateless response patterns
 * suitable for horizontally scaled deployments (ECS/EKS/ALB).</p>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Inventory service endpoint — externalised to application property.
     * Defaults to HTTPS to comply with cloud security standards (cr-java-0088).
     */
    @Value("${app.inventory.endpoint:https://inventory-svc.internal:8081/rooms}")
    private String inventoryEndpoint;

    /**
     * Report storage base path — externalised to application property (czr-java-001).
     */
    @Value("${app.report.base.path:/tmp/reports/}")
    private String reportBasePath;

    /**
     * Creates a new booking and returns the booking details in the response body.
     * Session state is no longer used — the booking data is returned directly
     * to the caller, making this endpoint safe for stateless, load-balanced deployments.
     *
     * @param guestName Guest's full name
     * @param roomType  Room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn   Check-in date
     * @param checkOut  Check-out date
     * @return Confirmation response containing booking details
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Fixed cr-java-0065: Removed HttpSession usage — booking state is returned in the
        // response body instead of stored in server-side session memory.
        // Fixed cr-java-0067: Removed static in-memory bookingCache — not safe for
        // horizontally scaled deployments. Use a distributed cache (Redis/ElastiCache) if caching is needed.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves the status of an existing booking by its ID.
     *
     * @param bookingId The booking identifier
     * @return Map containing booking details
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        // Fixed cr-java-0065: Removed HttpSession dependency — endpoint is now fully stateless.
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability for the requested room type.
     * The inventory service URL is injected from application properties (HTTPS enforced).
     *
     * @param roomType Room category to check
     * @return Availability response including the resolved inventory endpoint
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Fixed cr-java-0088: inventoryEndpoint is injected from application property,
        // defaulting to HTTPS — no hardcoded plain-HTTP URL in source code.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns the download path for a monthly booking report.
     * The report base path is injected from application properties (czr-java-001).
     *
     * @param month Month identifier for the report
     * @return Map containing the resolved report path and generation message
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Fixed czr-java-001: reportBasePath is injected from application property —
        // no hardcoded absolute path in source code.
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
