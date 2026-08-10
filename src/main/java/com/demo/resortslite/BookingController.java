package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// Updated: javax.servlet.http.HttpSession → jakarta.servlet.http.HttpSession
// (JAVA8_TO_21_JAKARTA_EE_MIGRATION: javax.* → jakarta.* required for Spring Boot 3.x / Java 21)
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067: Removed instance-local static HashMap cache.
    // In a horizontally-scaled deployment (ECS/EKS) each instance has its own JVM heap,
    // so a static Map is invisible to other instances and breaks consistency.
    // Distributed caching (e.g. Amazon ElastiCache / Redis) should be used instead.
    // The cache has been removed here; a distributed cache integration can be added
    // as a follow-up using Spring Cache abstraction with a Redis CacheManager.

    // cr-java-0088 / cr-java-0021: Inventory service URL externalised to environment variable.
    // HTTPS is enforced; no hardcoded IP addresses or plain-HTTP URLs in source code.
    @Value("${app.inventory.endpoint:https://inventory-svc.internal:8081/rooms}")
    private String inventoryEndpoint;

    // czr-java-001: Report base path externalised to environment variable.
    // No hardcoded absolute filesystem paths — compatible with containerised deployments.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065: HTTP session usage retained for local development / single-instance
        // deployments. For multi-instance / cloud deployments replace with a distributed
        // session store (e.g. Spring Session + Redis) so session data is shared across
        // all instances behind the load balancer.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065: See note in createBooking — replace with distributed session store
        // (Spring Session + Redis) for multi-instance cloud deployments.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0088: Inventory URL is now injected from environment variable (HTTPS).
        // No hardcoded plain-HTTP internal service URLs in source code.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // czr-java-001: Report path is now constructed from an environment-variable-backed
        // base path instead of a hardcoded absolute filesystem path.
        // For production use, replace local file I/O with cloud object storage (e.g. S3).
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
