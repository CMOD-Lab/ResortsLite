package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

// jakarta.servlet replaces javax.servlet — required for Spring Boot 3.x / Jakarta EE 10
import jakarta.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller exposing booking management endpoints for the ResortsLite application.
 *
 * <p>All internal service URLs are resolved from environment variables to support
 * cloud-native deployment across multiple environments without code changes.</p>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * In-memory booking cache backed by a {@link ConcurrentHashMap}.
     *
     * <p><strong>Note:</strong> For production horizontal scaling, replace with a
     * distributed cache such as Redis / AWS ElastiCache to share state across
     * multiple application instances.</p>
     */
    private static final Map<String, Object> bookingCache = new ConcurrentHashMap<>();

    /**
     * Creates a new booking for the specified guest and room details.
     *
     * @param guestName name of the guest
     * @param roomType  room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn   check-in date string (ISO-8601 recommended)
     * @param checkOut  check-out date string (ISO-8601 recommended)
     * @param session   current HTTP session used to store last-booking context
     * @return a map containing confirmation status and booking details
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // NOTE: Session usage retained for compatibility; for cloud-native horizontal scaling
        // consider externalising session state to a distributed store (e.g., Spring Session + Redis).
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        bookingCache.put((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves the status and details of an existing booking.
     *
     * @param bookingId unique booking identifier
     * @param session   current HTTP session used to retrieve last-guest context
     * @return a map containing booking details and session guest name
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability for the requested room type.
     *
     * <p>The inventory service URL is resolved from the {@code INVENTORY_API_URL}
     * environment variable; updated from plain HTTP to HTTPS.</p>
     *
     * @param roomType room category to check
     * @return a map containing availability status and inventory endpoint reference
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Updated to HTTPS endpoint; externalise URL via environment variable / config server
        // for cloud-native deployments.
        String inventoryUrl = System.getenv().getOrDefault(
                "INVENTORY_API_URL", "https://inventory-service.internal:8081/rooms/available");

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns the download path for a monthly booking report.
     *
     * <p>The report base path is resolved from the {@code REPORT_BASE_PATH}
     * environment variable, falling back to the JVM temp directory for portability.</p>
     *
     * @param month the month identifier used to locate the report file
     * @return a map containing the report path and generation status message
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Hardcoded absolute path replaced with a configurable path via environment variable.
        // Default falls back to the system temp directory for portability across environments.
        String reportBase = System.getenv().getOrDefault(
                "REPORT_BASE_PATH",
                System.getProperty("java.io.tmpdir") + "/reports");
        String reportPath = reportBase + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
