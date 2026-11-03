package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(jdbcTemplate);
    }

    @Test
    void createBooking_shouldPersistAndReturnBookingDetails() {
        Map<String, Object> booking = bookingService.createBooking("John Smith", "SUITE", "2024-03-01", "2024-03-05");

        verify(jdbcTemplate).update(eq("INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)"), any(), eq("John Smith"), eq("SUITE"), eq("2024-03-01"), eq("2024-03-05"));
        assertNotNull(booking.get("bookingId"));
        assertTrue(booking.get("bookingId").toString().startsWith("BK-"));
        assertEquals("John Smith", booking.get("guestName"));
        assertEquals("SUITE", booking.get("roomType"));
        assertEquals("2024-03-01", booking.get("checkIn"));
        assertEquals("2024-03-05", booking.get("checkOut"));
        assertEquals("jdbc:h2:mem:resortdb", booking.get("dbHost"));
        assertEquals("sa", booking.get("dbUser"));
        assertEquals("", booking.get("dbPass"));
        assertEquals(64, booking.get("confirmationCode").toString().length());
    }

    @Test
    void getBookingById_shouldReturnDatabaseResultWhenFound() {
        Map<String, Object> dbResult = Map.of("id", "BK-123", "guest", "Jane Doe");
        when(jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", "BK-123")).thenReturn(dbResult);

        Map<String, Object> result = bookingService.getBookingById("BK-123");

        assertEquals(dbResult, result);
    }

    @Test
    void getBookingById_shouldReturnErrorWhenQueryFails() {
        when(jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", "BK-404")).thenThrow(new RuntimeException("missing"));

        Map<String, Object> result = bookingService.getBookingById("BK-404");

        assertEquals("Booking not found: BK-404", result.get("error"));
    }

    @Test
    void calculateRoomPrice_shouldApplyPeakSeasonLoyaltyAndLongStayDiscounts() {
        String total = bookingService.calculateRoomPrice("SUITE", 14, "PEAK", "GOLD");

        assertEquals("5953.50", total);
    }

    @Test
    void calculateRoomPrice_shouldUseDefaultBasePriceForUnknownRoomType() {
        String total = bookingService.calculateRoomPrice("CABIN", 2, "OFF", "NONE");

        assertEquals("192.00", total);
    }

    @Test
    void calculateRoomPrice_shouldApplyWeeklyDiscountForPlatinumGuest() {
        String total = bookingService.calculateRoomPrice("DELUXE", 7, "REGULAR", "PLATINUM");

        assertEquals("1064.00", total);
    }

    @Test
    void isRoomAvailable_shouldReturnTrueForSupportedRoomType() {
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_shouldReturnFalseForUnsupportedRoomType() {
        assertFalse(bookingService.isRoomAvailable("TENT"));
    }

    @Test
    void generateReport_shouldIncludeMonthAndPaymentApi() {
        String message = bookingService.generateReport("March");

        assertEquals("Report generation triggered for: March via http://localhost/payments/charge", message);
    }
}
