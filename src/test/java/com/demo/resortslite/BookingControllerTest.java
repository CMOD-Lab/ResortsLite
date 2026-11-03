package com.demo.resortslite;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @Mock
    private HttpSession session;

    private BookingController bookingController;

    @BeforeEach
    void setUp() {
        bookingController = new BookingController(bookingService);
    }

    @Test
    void createBooking_shouldStoreSessionValuesAndReturnConfirmedResponse() {
        Map<String, Object> booking = Map.of("bookingId", "BK-111", "guestName", "John Smith");
        when(bookingService.createBooking("John Smith", "SUITE", "2024-03-01", "2024-03-05")).thenReturn(booking);

        Map<String, Object> response = bookingController.createBooking("John Smith", "SUITE", "2024-03-01", "2024-03-05", session);

        verify(session).setAttribute("lastBooking", booking);
        verify(session).setAttribute("guestName", "John Smith");
        assertEquals("confirmed", response.get("status"));
        assertEquals(booking, response.get("booking"));
    }

    @Test
    void getBookingStatus_shouldReturnSessionGuestAndBookingDetails() {
        Map<String, Object> details = Map.of("id", "BK-222");
        when(session.getAttribute("guestName")).thenReturn("Jane Doe");
        when(bookingService.getBookingById("BK-222")).thenReturn(details);

        Map<String, Object> response = bookingController.getBookingStatus("BK-222", session);

        assertEquals("BK-222", response.get("bookingId"));
        assertEquals("Jane Doe", response.get("sessionGuest"));
        assertEquals(details, response.get("details"));
    }

    @Test
    void checkAvailability_shouldReturnInventoryEndpointAndAvailability() {
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        assertEquals("DELUXE", response.get("roomType"));
        assertEquals("http://localhost/rooms/available", response.get("inventoryEndpoint"));
        assertEquals(true, response.get("available"));
    }

    @Test
    void downloadReport_shouldReturnReportPathAndServiceMessage() {
        when(bookingService.generateReport("March")).thenReturn("generated");

        Map<String, Object> response = bookingController.downloadReport("March");

        assertEquals("reports/March_bookings.pdf", response.get("reportPath"));
        assertEquals("generated", response.get("message"));
    }
}
