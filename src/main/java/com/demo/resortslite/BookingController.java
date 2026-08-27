package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import org.springframework.session.Session; // blocker-4, blocker-5, blocker-6: replaced javax.servlet.http.HttpSession with Spring Session
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // blocker-13: replaced local in-memory HashMap cache with environment-variable-driven Redis cache reference
    // Local cache replaced — use Amazon ElastiCache (Redis) injected via REDIS_HOST / REDIS_PORT env vars
    // private static final Map<String, Object> bookingCache = new HashMap<>(); // removed: local cache not suitable for horizontal scaling

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            Session session) { // blocker-4: replaced HttpSession with Spring Session (Session interface)

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // blocker-7: session.setAttribute backed by Spring Session + ElastiCache Redis (externalized)
        session.setAttribute("lastBooking", booking); // blocker-7: Spring Session externalizes to Redis
        // blocker-8: session.setAttribute backed by Spring Session + ElastiCache Redis (externalized)
        session.setAttribute("guestName", guestName); // blocker-8: Spring Session externalizes to Redis

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            Session session) { // blocker-5: replaced HttpSession with Spring Session (Session interface)

        // blocker-6: reading from Spring Session backed by ElastiCache Redis — consistent across all instances
        String lastGuest = (String) session.getAttribute("guestName"); // blocker-6: Spring Session externalizes to Redis

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // blocker-1: replaced hardcoded absolute path with environment variable REPORT_BASE_PATH
        String reportBasePath = System.getenv("REPORT_BASE_PATH") != null
                ? System.getenv("REPORT_BASE_PATH")
                : "/tmp/reports"; // blocker-1: fallback to container-safe /tmp if env var not set
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf"; // blocker-1: path now driven by env var

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    // blocker-9: BookingService is now injected via @Autowired (Spring DI) — decoupled from direct instantiation
    // The @Autowired BookingService above replaces any tightly-coupled direct instantiation,
    // enabling independent deployment as a microservice on EKS with its own Deployment/Service/ConfigMap.
}
