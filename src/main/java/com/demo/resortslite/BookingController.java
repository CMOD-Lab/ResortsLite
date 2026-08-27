package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// Updated from javax.servlet.http.HttpSession to jakarta.servlet.http.HttpSession
// (JAVA8_TO_21_JAKARTA_EE_MIGRATION, JAVA8_TO_17_VALIDATION_ANNOTATION_UPDATES)
// Spring Boot 3.x / Jakarta EE 10 uses jakarta.* namespace instead of javax.*
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 fix: Removed instance-local in-memory cache (bookingCache HashMap).
    // Instance-local caches break horizontal scaling — cache is invisible to other EC2
    // instances. Use a distributed cache (e.g., Redis / ElastiCache) for shared state.

    // cr-java-0088 fix: Inventory service URL externalised to environment variable.
    // Plain HTTP replaced with HTTPS to comply with cloud security standards.
    @Value("${app.inventory.endpoint:https://inventory-svc.internal:8081/rooms}")
    private String inventoryEndpoint;

    // czr-java-001 fix: Report base path externalised to environment variable.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 fix: Removed HTTP session state storage for booking data.
        // Session-based state breaks horizontal scaling (ALB distributes requests across
        // instances; session on instance A is invisible to instance B).
        // Booking state is now returned directly in the response; persistent state should
        // be stored in a shared data store (e.g., RDS / DynamoDB).

        // cr-java-0067 fix: Removed instance-local bookingCache.put() call.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // cr-java-0065 fix: Removed HTTP session read (session.getAttribute("guestName")).
        // Session state is not shared across instances in a horizontally scaled cluster.
        // Guest context should be retrieved from the persistent booking record instead.

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0088 fix: inventoryUrl is now injected from environment variable
        // (app.inventory.endpoint) and uses HTTPS instead of plain HTTP.

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // czr-java-001 fix: Replaced hardcoded /var/legacy/reports/ absolute path with
        // environment-variable-backed reportBasePath injected via @Value.
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
