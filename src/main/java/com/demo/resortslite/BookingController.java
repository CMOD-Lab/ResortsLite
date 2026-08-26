package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// Updated: javax.servlet.http.HttpSession → jakarta.servlet.http.HttpSession
// Reason: Spring Boot 3.x / Jakarta EE 10 replaced the javax.* namespace with
// jakarta.*. Using the old javax.servlet import causes a compilation error on
// Spring Boot 3.x + Java 21.
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Updated: Inventory endpoint externalised to environment variable via
    // application.properties. Replaced hardcoded plain-HTTP internal hostname
    // with an injected value that defaults to HTTPS (cr-java-0088 / cr-java-0021).
    @Value("${app.inventory.endpoint:https://inventory-svc.internal:8081/rooms}")
    private String inventoryEndpoint;

    // Updated: Report base path externalised to environment variable.
    // Hardcoded absolute path /var/legacy/reports replaced with a configurable
    // value so it works in containers and cloud environments (czr-java-001).
    @Value("${app.report.base-path:${java.io.tmpdir}/reports}")
    private String reportBasePath;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        // Updated: HTTP session usage removed (cr-java-0065).
        // Session-based state is incompatible with horizontal scaling — AWS ALB
        // distributes requests across instances and session data stored on one
        // instance is invisible to others. Booking state is now returned directly
        // in the response body; callers should persist state client-side or in a
        // shared store (e.g. Redis / DynamoDB) if cross-request state is needed.
        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Updated: In-memory static cache removed (cr-java-0067).
        // A static HashMap is instance-local and breaks horizontal scaling.
        // Distributed caching (e.g. AWS ElastiCache / Redis) should be used
        // when a shared cache is required across multiple instances.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // Updated: HTTP session dependency removed (cr-java-0065).
        // Session attributes are not shared across scaled instances.
        // Booking details are now fetched directly from the database via
        // bookingService.getBookingById(), which is the authoritative source.
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Updated: Hardcoded plain-HTTP internal URL replaced with injected
        // HTTPS endpoint from application.properties / environment variable
        // (cr-java-0088 / cr-java-0021). The actual HTTP call to the inventory
        // service should be made via a properly configured RestClient/WebClient
        // with TLS; the endpoint is surfaced here for diagnostic purposes only.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Updated: Hardcoded absolute path /var/legacy/reports replaced with
        // an injected, environment-configurable base path (czr-java-001).
        // In production this should point to a cloud object-storage pre-signed
        // URL (S3 / Azure Blob) rather than a local filesystem path.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
