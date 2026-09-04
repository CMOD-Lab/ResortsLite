package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for booking operations.
 * Exposes endpoints for creating bookings, checking availability,
 * retrieving booking status, and downloading reports.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Updated: Externalised inventory service URL to environment variable / application property.
    // Replaces hardcoded plain HTTP URL (fixes cr-java-0088, czr-java-001).
    @Value("${app.inventory.endpoint:https://inventory-svc.internal/rooms}")
    private String inventoryEndpoint;

    // Updated: Externalised report base path to environment variable / application property.
    // Replaces hardcoded /var/legacy/reports path (fixes czr-java-001).
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // Updated: Removed in-memory static bookingCache (fixes cr-java-0067).
    // In-memory caches are instance-local and break horizontal scaling in ECS/EKS.
    // Distributed caching (e.g., Amazon ElastiCache / Redis) should be used instead.

    /**
     * Creates a new booking and returns the confirmed booking details.
     * Booking state is no longer stored in HTTP session to support stateless
     * horizontal scaling across multiple instances (fixes cr-java-0065, cr-java-0067).
     *
     * @param guestName  Name of the guest
     * @param roomType   Room category
     * @param checkIn    Check-in date
     * @param checkOut   Check-out date
     * @return Confirmed booking details
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Updated: Removed HttpSession usage — booking state must not be stored in
        // server-side HTTP session for stateless cloud-native deployments (fixes cr-java-0065).
        // Clients should use the returned bookingId for subsequent requests.

        // Updated: Removed in-memory bookingCache.put() — instance-local cache breaks
        // horizontal scaling (fixes cr-java-0067). Use distributed cache (Redis/ElastiCache).

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves the status of an existing booking by its ID.
     * Session-based guest lookup removed to support stateless scaling (fixes cr-java-0065).
     *
     * @param bookingId  The booking identifier
     * @return Booking status and details
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // Updated: Removed HttpSession.getAttribute("guestName") — reading business state
        // from HTTP session fails on any other instance in the cluster (fixes cr-java-0065).
        // Guest information is retrieved directly from the database via bookingService.

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability for the given room type.
     * Inventory service URL is externalised and uses HTTPS (fixes cr-java-0088).
     *
     * @param roomType  Room category to check
     * @return Availability status and inventory endpoint reference
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Updated: Inventory URL sourced from externalised property (HTTPS enforced).
        // Replaces hardcoded plain HTTP URL (fixes cr-java-0088, czr-java-001).

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns the report download path for the given month.
     * Report path is sourced from externalised configuration (fixes czr-java-001).
     *
     * @param month  Month identifier for the report
     * @return Report path and generation message
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Updated: Report path constructed from externalised property instead of
        // hardcoded /var/legacy/reports path (fixes czr-java-001).
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
