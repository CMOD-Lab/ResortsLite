package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for managing resort bookings.
 * Migrated to Jakarta Servlet API (jakarta.servlet) for Spring Boot 3 / Java 21 compatibility.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // NOTE: In-memory cache is instance-local; consider a distributed cache (e.g., Redis)
    // for horizontal scaling in cloud environments.
    private static final Map<String, Object> bookingCache = new HashMap<>();

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // NOTE: Session-based state storage is not suitable for clustered/cloud deployments.
        // Consider externalising session state to a distributed store (e.g., Spring Session + Redis).
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        bookingCache.put((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // NOTE: Internal service URLs should be externalised to environment variables
        // or a service registry for cloud-native deployments.
        String inventoryUrl = System.getenv().getOrDefault(
                "INVENTORY_SERVICE_URL", "https://inventory-service.internal:8081/rooms/available");

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // NOTE: Report paths should be externalised to environment variables or
        // cloud object storage (e.g., S3) rather than hardcoded filesystem paths.
        String reportBasePath = System.getenv().getOrDefault("REPORT_BASE_PATH", "/tmp/reports");
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
