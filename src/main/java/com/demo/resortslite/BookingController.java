package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Inventory service endpoint — externalised to an environment variable so that
     * the URL can be configured per environment without code changes.
     * Defaults to HTTPS to comply with cloud security standards.
     * (Fixes: cr-java-0088, cr-java-0021)
     */
    @Value("${app.inventory.endpoint:https://inventory-svc.internal:8081/rooms}")
    private String inventoryEndpoint;

    /**
     * Creates a new booking.
     *
     * <p>Session usage has been removed for booking state storage; the booking
     * details are returned directly in the response body so that the API is
     * stateless and compatible with horizontally-scaled deployments behind an
     * AWS ALB or EKS ingress.
     * (Fixes: cr-java-0065, cr-java-0067)
     *
     * @param guestName guest's full name
     * @param roomType  room category
     * @param checkIn   check-in date
     * @param checkOut  check-out date
     * @return confirmation response containing booking details
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the current status of a booking.
     *
     * <p>Booking state is retrieved from the database rather than from HTTP session
     * memory, ensuring correctness in multi-instance deployments.
     * (Fixes: cr-java-0065)
     *
     * @param bookingId the booking identifier
     * @return map containing booking details
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability for the requested room type.
     *
     * <p>The inventory service URL is injected via {@code app.inventory.endpoint}
     * and defaults to an HTTPS endpoint.
     * (Fixes: cr-java-0088)
     *
     * @param roomType room category to check
     * @return availability response
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns the download path for a monthly booking report.
     *
     * <p>The report path is no longer hardcoded; it is derived from the configured
     * report base path so that it resolves correctly inside container environments.
     * (Fixes: czr-java-001)
     *
     * @param month the month for which the report is requested
     * @return response containing report path and generation status
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
