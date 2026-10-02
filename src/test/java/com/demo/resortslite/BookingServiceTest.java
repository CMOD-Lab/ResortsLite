package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BookingService.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // -----------------------------------------------------------------------
    // createBooking
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsBookingMap() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertEquals("Alice", result.get("guestName"));
        assertEquals("SUITE", result.get("roomType"));
        assertEquals("2024-06-01", result.get("checkIn"));
        assertEquals("2024-06-05", result.get("checkOut"));
        assertNotNull(result.get("bookingId"));
        assertNotNull(result.get("confirmationCode"));
    }

    @Test
    void createBooking_bookingIdStartsWithBK() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    void createBooking_confirmationCodeIsNotEmpty() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "STANDARD", "2024-08-10", "2024-08-12");

        // Assert
        String confirmCode = (String) result.get("confirmationCode");
        assertNotNull(confirmCode);
        assertFalse(confirmCode.isEmpty());
    }

    @Test
    void createBooking_jdbcUpdateIsCalledOnce() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Dave", "VILLA", "2024-09-01", "2024-09-10");

        // Assert
        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void createBooking_containsDbHostKey() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-05");

        // Assert
        assertTrue(result.containsKey("dbHost"));
    }

    // -----------------------------------------------------------------------
    // getBookingById
    // -----------------------------------------------------------------------

    @Test
    void getBookingById_whenFound_returnsBookingData() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-12345678");
        dbRow.put("guest", "Alice");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-12345678"))).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-12345678");

        // Assert
        assertNotNull(result);
        assertEquals("Alice", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), anyString()))
                .thenThrow(new RuntimeException("No rows found"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-UNKNOWN");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("BK-UNKNOWN"));
    }

    @Test
    void getBookingById_errorMessageContainsBookingId() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), anyString()))
                .thenThrow(new RuntimeException("empty result set"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-MISSING");

        // Assert
        String error = (String) result.get("error");
        assertTrue(error.contains("BK-MISSING"));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — room type variations
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoom_normalSeasonNoLoyalty() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");

        // Assert — 120.0 * 1 = 120.00
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoom_normalSeasonNoLoyalty() {
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoom_normalSeasonNoLoyalty() {
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoom_normalSeasonNoLoyalty() {
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_defaultsToStandard() {
        // Unknown room type defaults to 120.0
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — season variations
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_peakSeason_appliesMultiplier() {
        // STANDARD 120 * 1.5 = 180 * 1 night = 180.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_offSeason_appliesDiscount() {
        // STANDARD 120 * 0.8 = 96 * 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_defaultSeason_noMultiplier() {
        // STANDARD 120 * 1 = 120 * 2 nights = 240.00
        String price = bookingService.calculateRoomPrice("STANDARD", 2, "REGULAR", "NONE");
        assertEquals("240.00", price);
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — loyalty tier variations
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_goldLoyalty_tenPercentDiscount() {
        // STANDARD 120 * 0.9 = 108 * 1 = 108.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyalty_twentyPercentDiscount() {
        // STANDARD 120 * 0.8 = 96 * 1 = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_thirtyPercentDiscount() {
        // STANDARD 120 * 0.7 = 84 * 1 = 84.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_noLoyalty_noDiscount() {
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "REGULAR");
        assertEquals("120.00", price);
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — long-stay discounts
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_sevenNights_fivePercentLongStayDiscount() {
        // STANDARD 120 * 0.95 = 114 * 7 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_tenPercentLongStayDiscount() {
        // STANDARD 120 * 0.90 = 108 * 14 = 1512.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_sixNights_noLongStayDiscount() {
        // STANDARD 120 * 6 = 720.00
        String price = bookingService.calculateRoomPrice("STANDARD", 6, "NORMAL", "NONE");
        assertEquals("720.00", price);
    }

    @Test
    void calculateRoomPrice_fifteenNights_tenPercentLongStayDiscount() {
        // STANDARD 120 * 0.90 = 108 * 15 = 1620.00
        String price = bookingService.calculateRoomPrice("STANDARD", 15, "NORMAL", "NONE");
        assertEquals("1620.00", price);
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — combined scenarios
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_peakSeasonGoldLoyaltySevenNights() {
        // SUITE 350 * 1.5 = 525 * 0.9 = 472.5 * 0.95 = 448.875 * 7 = 3142.13
        String price = bookingService.calculateRoomPrice("SUITE", 7, "PEAK", "GOLD");
        assertEquals("3142.13", price);
    }

    @Test
    void calculateRoomPrice_offSeasonDiamondLoyaltyFourteenNights() {
        // VILLA 600 * 0.8 = 480 * 0.7 = 336 * 0.90 = 302.4 * 14 = 4233.60
        String price = bookingService.calculateRoomPrice("VILLA", 14, "OFF", "DIAMOND");
        assertEquals("4233.60", price);
    }

    // -----------------------------------------------------------------------
    // isRoomAvailable
    // -----------------------------------------------------------------------

    @Test
    void isRoomAvailable_standardRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
    }

    @Test
    void isRoomAvailable_deluxeRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
    }

    @Test
    void isRoomAvailable_suiteRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("SUITE"));
    }

    @Test
    void isRoomAvailable_villaRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_unknownRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    void isRoomAvailable_nullRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(null));
    }

    @Test
    void isRoomAvailable_emptyString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_lowercaseRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("suite"));
    }

    // -----------------------------------------------------------------------
    // generateReport
    // -----------------------------------------------------------------------

    @Test
    void generateReport_returnsStringContainingMonth() {
        String result = bookingService.generateReport("2024-03");
        assertNotNull(result);
        assertTrue(result.contains("2024-03"));
    }

    @Test
    void generateReport_returnsNonEmptyString() {
        String result = bookingService.generateReport("2024-12");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void generateReport_containsTriggeredMessage() {
        String result = bookingService.generateReport("2024-01");
        assertTrue(result.toLowerCase().contains("report"));
    }
}
