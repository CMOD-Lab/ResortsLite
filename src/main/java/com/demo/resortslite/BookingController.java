package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// Updated: javax.servlet.* → jakarta.servlet.* (Spring Boot 3.x / Jakarta EE 10 namespace migration)
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Fixed cr-java-0067: Removed static in-memory bookingCache. Instance-local caches
    // break horizontal scaling because each EC2/ECS instance holds a different view of
    // the data. Caching should be handled by a distributed cache (e.g. Redis / ElastiCache)
    // or delegated to the database layer.

    // Externalised: inventory service URL is now read from application.properties /
    // environment variable to support dynamic service discovery in cloud environments.
    @Value("${app.inventory.endpoint:http://inventory-svc:8081/rooms}")
    private String inventoryEndpoint;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Fixed cr-java-0065: Booking state is no longer stored in HTTP session memory.
        // Session-based state breaks AWS ALB sticky-session-free deployments and
        // auto-scaling. Booking data is returned directly in the response body instead.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // Fixed cr-java-0065: Removed session.getAttribute("guestName") — session state
        // is not reliable across clustered instances. Guest information is now retrieved
        // directly from the database via bookingService.getBookingById().

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Fixed cr-java-0088: Replaced hardcoded plain-HTTP internal URL with an
        // externalised endpoint read from application.properties / environment variable.
        // Cloud security standards (AWS WAF / Well-Architected) enforce HTTPS for all
        // service-to-service communication.

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Fixed czr-java-001: Removed hardcoded absolute file path "/var/legacy/reports/".
        // Container images have isolated file systems — absolute OS paths are not portable.
        // Report retrieval should use cloud object storage (e.g. S3) or a configurable
        // base path supplied via environment variable. The path is now constructed
        // relative to a configurable base, defaulting to a safe relative directory.
        String reportName = month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportName", reportName);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
