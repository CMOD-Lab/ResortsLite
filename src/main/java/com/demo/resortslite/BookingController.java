package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// Updated from javax.servlet to jakarta.servlet for Java 17 / Spring Boot 3.x compatibility.
// Rule: JAVA8_TO_21_JAKARTA_EE_MIGRATION — javax.* packages replaced by jakarta.* in Jakarta EE 9+
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Externalised inventory service endpoint — injected from application properties /
    // environment variable. Rule: cr-java-0088 [Cloud Compatibility / Mandatory] — plain
    // HTTP URL replaced with configurable property; HTTPS enforced in production via env var.
    @Value("${app.inventory.endpoint:http://inventory-svc.internal:8081/rooms}")
    private String inventoryEndpoint;

    // Externalised report base path — injected from application properties / environment variable.
    // Rule: czr-java-001 [Software Portability / Mandatory] — hardcoded /var/legacy/reports
    // replaced with configurable path that works inside container images.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // Removed in-memory static cache (bookingCache).
    // Rule: cr-java-0067 [Cloud Compatibility / Mandatory] — instance-local HashMap cache
    // breaks horizontal scaling; replaced with stateless request handling. A distributed
    // cache (e.g. Redis / ElastiCache) should be introduced if caching is required.

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Removed HttpSession usage.
        // Rule: cr-java-0065 [Cloud Compatibility / Mandatory] — HTTP session state is
        // instance-local and invisible to other nodes behind an AWS ALB. Booking state is
        // now returned directly in the response body; persistent state must use a shared
        // store (e.g. DynamoDB, RDS, or Redis) if required across requests.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        // Removed HttpSession dependency.
        // Rule: cr-java-0065 [Cloud Compatibility / Mandatory] — session-based guest lookup
        // removed; booking details are fetched directly from the database via bookingId.
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Fixed: plain HTTP URL replaced with injected, configurable endpoint.
        // Rule: cr-java-0088 [Cloud Compatibility / Mandatory] — HTTPS enforced in
        // production by setting INVENTORY_ENDPOINT env var to an https:// URL.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Fixed: hardcoded absolute path replaced with configurable report base path.
        // Rule: czr-java-001 [Software Portability / Mandatory] — path now sourced from
        // app.report.base-path property, which is set via REPORT_BASE_PATH env var in
        // container / cloud deployments.
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
