package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// [JAVA8_TO_21_JAKARTA_EE_MIGRATION / JAVA8_TO_11_DEPRECATED_APIS]
// Replaced: import javax.servlet.http.HttpSession;
// With:     import jakarta.servlet.http.HttpSession;
// Reason:   Spring Boot 3.x is built on Jakarta EE 10. The javax.servlet package was
//           removed from the JDK module path and is no longer provided by Spring Boot 3.x.
//           All javax.servlet.* imports must be migrated to jakarta.servlet.* equivalents.
//           Failure to do so causes: "package javax.servlet does not exist" compilation error.
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // [CLOUD_COMPATIBILITY / PORTABILITY]: Inventory service endpoint externalised to
    // environment variable via @Value injection (resolves cr-java-0088, cr-java-0021).
    // The default value uses HTTPS to comply with cloud security standards (AWS ALB / WAF).
    @Value("${app.inventory.endpoint:https://inventory-svc.internal/rooms}")
    private String inventoryEndpoint;

    // [PORTABILITY]: Report base path externalised to environment variable so the
    // controller does not embed OS-specific or container-incompatible paths (resolves czr-java-001).
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // [CLOUD_COMPATIBILITY]: Removed static in-memory bookingCache (resolves cr-java-0067).
    // Instance-local caches break horizontal scaling — each EC2/ECS instance holds a
    // different view of the data. Use a distributed cache (e.g. Amazon ElastiCache / Redis)
    // or rely on the database for authoritative state.

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // [CLOUD_COMPATIBILITY]: HTTP session attributes for business state removed
        // (resolves cr-java-0065). AWS ALB distributes requests across EC2 instances —
        // session data on instance A is invisible to instance B. Booking state is now
        // persisted exclusively in the PostgreSQL database and returned in the response.
        // If sticky sessions or a distributed session store (e.g. Spring Session + Redis)
        // are required, configure them at the infrastructure level.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // [CLOUD_COMPATIBILITY]: Removed session.getAttribute("guestName") — reading
        // business state from HTTP session is unreliable in a multi-instance cluster
        // (resolves cr-java-0065). Guest information is now retrieved from the database.

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // [CLOUD_COMPATIBILITY / SECURITY]: Replaced hardcoded plain-HTTP internal URL
        // with the externalised HTTPS endpoint injected via @Value (resolves cr-java-0088,
        // cr-java-0021). HTTPS is enforced by AWS ALB, WAF, and Well-Architected security review.

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // [PORTABILITY]: Replaced hardcoded absolute path /var/legacy/reports with the
        // environment-variable-backed reportBasePath (resolves czr-java-001).
        // In production, point REPORT_BASE_PATH to a mounted volume or an S3-compatible path.
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
