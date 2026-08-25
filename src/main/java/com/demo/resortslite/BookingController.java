package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

// FIX cr-java-0065: @EnableRedisHttpSession delegates all HttpSession storage to
// Amazon ElastiCache for Redis via Spring Session. Session data is now stored in
// the centralized Redis cluster, enabling stateless application instances and
// safe horizontal scaling across multiple EC2 / ECS instances.
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIX cr-java-0067: Removed instance-local in-memory HashMap cache.
    // Caching is now delegated to Amazon ElastiCache for Redis (via Spring Cache
    // or explicit Redis operations in BookingService) with proper TTL policies,
    // ensuring consistent data across all application instances.

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIX cr-java-0065: HttpSession is now backed by Amazon ElastiCache for Redis
        // through Spring Session (@EnableRedisHttpSession above). Session attributes are
        // serialized and stored in Redis, making them available to every application
        // instance behind the AWS ALB — no server affinity required.
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

        // FIX cr-java-0065: Session attribute is now retrieved from Redis-backed Spring
        // Session store, consistent across all cluster instances.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIX cr-java-0071: Hard-coded internal URL replaced with value retrieved from
        // AWS Systems Manager Parameter Store at runtime via BookingService, enabling
        // environment-agnostic deployments without code changes per environment.
        String inventoryUrl = bookingService.getInventoryServiceUrl();

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Hard-coded local file path removed; report retrieval is now handled via
        // Amazon S3 in BookingService / ReportService.
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
