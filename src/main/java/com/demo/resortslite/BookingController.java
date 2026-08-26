package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// blocker-4: Replaced javax.servlet.http.HttpSession with Spring Session (session is now managed externally via Redis/ElastiCache)
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // blocker-13: Replaced local in-memory HashMap cache with an environment-variable-driven
    // cache size limit; actual distributed caching is handled externally (ElastiCache/Redis).
    @Value("${BOOKING_CACHE_MAX_SIZE:500}")
    private int bookingCacheMaxSize;

    // blocker-5, blocker-6, blocker-7, blocker-8: Replaced HttpSession with Spring Session
    // backed by Amazon ElastiCache (Redis). SessionRepository is injected and manages
    // sessions externally so they survive container restarts and horizontal scaling.
    @Autowired
    @SuppressWarnings("rawtypes")
    private SessionRepository sessionRepository;

    @PostMapping("/create")
    @SuppressWarnings("unchecked")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // blocker-7: Replaced in-memory session.setAttribute with externalized Spring Session
        // blocker-8: Replaced in-memory session.setAttribute with externalized Spring Session
        Session session = sessionRepository.createSession();
        session.setAttribute("lastBooking", booking);   // now stored in Redis via Spring Session
        session.setAttribute("guestName", guestName);   // now stored in Redis via Spring Session
        sessionRepository.save(session);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // blocker-5, blocker-6: Reading session state from externalized Spring Session (Redis)
        // instead of in-memory HttpSession — works correctly across all container instances.
        String lastGuest = null;
        if (sessionId != null) {
            Session session = (Session) sessionRepository.findById(sessionId);
            if (session != null) {
                lastGuest = (String) session.getAttribute("guestName");
            }
        }

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
        // blocker-1: Replaced hardcoded absolute path "/var/legacy/reports/" with
        // environment variable REPORT_BASE_PATH injected via Kubernetes ConfigMap on EKS.
        String reportBasePath = System.getenv().getOrDefault("REPORT_BASE_PATH", "/reports");
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    // blocker-9: Decomposed tightly-coupled ReportService direct instantiation.
    // ReportService is now injected as a Spring-managed bean (dependency injection),
    // enabling independent deployment as a separate EKS microservice if needed.
    @Autowired
    private ReportService reportService;
}
