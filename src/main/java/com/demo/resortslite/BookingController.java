package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

// jakarta.servlet replaces javax.servlet (Jakarta EE 10 / Spring Boot 3.x migration)
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for booking operations.
 *
 * <p>Migration notes (Java 1.8 → Java 21 / Spring Boot 2.7.x → 3.2.x):
 * <ul>
 *   <li>javax.servlet.http.HttpSession → jakarta.servlet.http.HttpSession</li>
 *   <li>Plain HTTP internal service URLs replaced with HTTPS (security fix)</li>
 *   <li>Hardcoded report path replaced with environment-variable-driven path</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // In-memory cache — consider replacing with a distributed cache (e.g., Redis)
    // for horizontal scaling in cloud environments.
    private static final Map<String, Object> bookingCache = new HashMap<>();

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // NOTE: Session-based state may not survive across multiple instances.
        // Consider externalising session storage (e.g., Spring Session + Redis).
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        bookingCache.put((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

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

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Security fix: Use HTTPS for all internal service calls (was plain HTTP).
        // Endpoint resolved from environment variable for cloud portability.
        String inventoryUrl = System.getenv().getOrDefault(
                "INVENTORY_SERVICE_URL", "https://inventory-service.internal:8081/rooms/available");

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path resolved from environment variable — avoids hardcoded absolute paths
        // that break in containerised (Docker/ECS/EKS) deployments.
        String reportPath = System.getenv().getOrDefault("REPORT_BASE_PATH", "/var/reports/")
                + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
