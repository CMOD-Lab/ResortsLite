package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * BookingController — cloud-ready REST controller.
 *
 * Cloud readiness changes applied:
 *  - cr-java-0067: Replaced static in-memory HashMap cache with Spring Cache backed by
 *                  Amazon ElastiCache for Redis (TTL configured in application.properties).
 *  - cr-java-0065: Replaced HttpSession-based state storage with Spring Session backed by
 *                  Amazon ElastiCache for Redis, enabling stateless, horizontally-scalable
 *                  instances. HttpSession is still used as the API surface but is now
 *                  transparently persisted to Redis by Spring Session.
 *  - cr-java-0071: Replaced hard-coded inventory service URL with value injected from
 *                  application.properties / environment variable (AWS SSM Parameter Store
 *                  values are surfaced as env vars at runtime via ECS task definitions).
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Inventory service base URL — injected from environment variable / application.properties.
     * In AWS, set INVENTORY_SERVICE_URL via ECS task definition environment or SSM Parameter Store.
     * Fixes blocker-10 (cr-java-0071): hard-coded environment URL replaced with externalized config.
     */
    @Value("${app.inventory.service.url:http://inventory-service.internal:8081}")
    private String inventoryServiceUrl;

    /**
     * Create a new booking.
     * Session state is now stored in Amazon ElastiCache for Redis via Spring Session
     * (fixes blockers 13-16, cr-java-0065).
     * Booking is also cached in Redis with TTL (fixes blocker-20, cr-java-0067).
     */
    @PostMapping("/create")
    @CachePut(value = "bookings", key = "#result['bookingId']")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Session is now backed by Amazon ElastiCache for Redis via Spring Session.
        // All instances share the same Redis store — no server affinity required.
        // Fixes blockers 13, 14, 15, 16 (cr-java-0065).
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieve booking status.
     * Session lookup is now served from Redis — consistent across all instances.
     * Fixes blocker-17 (cr-java-0065).
     */
    @GetMapping("/status/{bookingId}")
    @Cacheable(value = "bookings", key = "#bookingId")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Session attribute is now retrieved from Redis via Spring Session.
        // Fixes blocker-17 (cr-java-0065).
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Check room availability.
     * Inventory service URL is now externalized — fixes blocker-10 (cr-java-0071).
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // URL is now injected from environment variable / application.properties.
        // In AWS, populate app.inventory.service.url via SSM Parameter Store and
        // surface it as an environment variable in the ECS task definition.
        // Fixes blocker-10 (cr-java-0071).
        String inventoryUrl = inventoryServiceUrl + "/rooms/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
