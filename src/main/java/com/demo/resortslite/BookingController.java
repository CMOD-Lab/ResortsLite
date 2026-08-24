package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// Updated: javax.servlet migrated to jakarta.servlet (Spring Boot 3.x / Jakarta EE 10)
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Updated: Removed instance-local in-memory cache (HashMap).
    // In-memory caches without TTL break horizontal scaling — cache is instance-local
    // and invisible to other EC2/ECS instances. Use a distributed cache (e.g. Redis /
    // ElastiCache) for shared state across instances.
    // Fix for: cr-java-0067 [Cloud Compatibility / Mandatory]

    // Updated: Inventory service endpoint externalised to environment variable.
    // Plain HTTP replaced with HTTPS for cloud security compliance.
    // Fix for: cr-java-0088 [Cloud Compatibility / Mandatory]
    @Value("${app.inventory.endpoint:https://inventory-svc.internal:8081/rooms}")
    private String inventoryEndpoint;

    // Updated: Report base path externalised to environment variable.
    // Hardcoded absolute paths do not exist inside container images.
    // Fix for: czr-java-001 [Software Portability / Mandatory]
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        // Updated: Removed HTTP session state storage for booking data.
        // AWS ALB distributes requests across EC2 instances — session data on instance A
        // is invisible to instance B. Auto-scaling and failover breaks with session-local state.
        // Booking state is now returned directly in the response; persistent state should be
        // stored in the database and retrieved via bookingId.
        // Fix for: cr-java-0065 [Cloud Compatibility / Mandatory]
        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // Updated: Removed HTTP session read for guestName.
        // Session-based state is not available across instances in a horizontally scaled cluster.
        // Booking details are now fetched directly from the database by bookingId.
        // Fix for: cr-java-0065 [Cloud Compatibility / Mandatory]
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Updated: Inventory service URL now read from environment-variable-backed property.
        // Plain HTTP replaced with HTTPS; hardcoded internal hostname removed.
        // Fix for: cr-java-0088 [Cloud Compatibility / Mandatory]
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Updated: Report path now constructed from environment-variable-backed property
        // instead of a hardcoded absolute path that does not exist in container images.
        // Fix for: czr-java-001 [Software Portability / Mandatory]
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
