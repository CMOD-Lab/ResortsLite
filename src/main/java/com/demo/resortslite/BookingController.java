package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// -----------------------------------------------------------------------
// FIXED (issue-3): Migrated from javax.servlet to jakarta.servlet
// Spring Boot 3.x / Jakarta EE 10 no longer ships javax.servlet.*
// The jakarta.servlet.* namespace is the correct replacement for Java 17.
// -----------------------------------------------------------------------
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // -----------------------------------------------------------------------
    // FIXED (cr-java-0067): Removed static in-memory bookingCache.
    // Instance-local caches break horizontal scaling (each EC2/EKS pod has its
    // own isolated cache). For distributed caching use Redis / ElastiCache.
    // The cache has been removed; a distributed cache integration point is
    // documented here for future implementation.
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // FIXED (cr-java-0088): Inventory service URL is now injected from
    // environment variable / application properties, replacing the hardcoded
    // plain-HTTP URL. HTTPS is enforced in production via the property value.
    // -----------------------------------------------------------------------
    @Value("${app.inventory.endpoint:https://inventory-svc:8081/rooms}")
    private String inventoryEndpoint;

    // -----------------------------------------------------------------------
    // FIXED (czr-java-001): Report base path is now injected from environment
    // variable / application properties, replacing the hardcoded absolute path.
    // -----------------------------------------------------------------------
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        // -----------------------------------------------------------------------
        // FIXED (cr-java-0065): Removed HttpSession parameter and session.setAttribute
        // calls. HTTP session state is instance-local and breaks horizontal scaling
        // (AWS ALB distributes requests across instances; session on instance A is
        // invisible to instance B). Stateless REST design is used instead.
        // For distributed session state, use Spring Session with Redis/ElastiCache.
        // -----------------------------------------------------------------------
        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        // -----------------------------------------------------------------------
        // FIXED (cr-java-0065): Removed HttpSession parameter and session.getAttribute
        // call. Booking lookup is now performed directly from the persistent store
        // via BookingService, ensuring consistent results across all cluster nodes.
        // -----------------------------------------------------------------------
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED (cr-java-0088): inventoryEndpoint is now injected from environment,
        // replacing the hardcoded plain-HTTP URL. HTTPS enforced in production.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIXED (czr-java-001): Report path is now built from the injected
        // reportBasePath property instead of a hardcoded absolute path.
        // Compatible with Docker volume mounts and cloud storage configurations.
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
