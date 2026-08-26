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
 * Unit tests for {@link BookingService}.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // -------------------------------------------------------------------------
    // createBooking tests
    // -------------------------------------------------------------------------

    @Test
    void createBooking_withValidInputs_returnsBookingMapWithExpectedKeys() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("bookingId"));
        assertTrue(result.containsKey("guestName"));
        assertTrue(result.containsKey("roomType"));
        assertTrue(result.containsKey("checkIn"));
        assertTrue(result.containsKey("checkOut"));
        assertTrue(result.containsKey("confirmationCode"));
        assertTrue(result.containsKey("dbHost"));
    }

    @Test
    void createBooking_bookingIdStartsWithBKPrefix() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertNotNull(bookingId);
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    void createBooking_guestNameStoredCorrectly() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Charlie", "STANDARD", "2024-08-01", "2024-08-04");

        // Assert
        assertEquals("Charlie", result.get("guestName"));
    }

    @Test
    void createBooking_roomTypeStoredCorrectly() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Diana", "VILLA", "2024-09-01", "2024-09-10");

        // Assert
        assertEquals("VILLA", result.get("roomType"));
    }

    @Test
    void createBooking_checkInAndCheckOutStoredCorrectly() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Eve", "STANDARD", "2024-10-01", "2024-10-07");

        // Assert
        assertEquals("2024-10-01", result.get("checkIn"));
        assertEquals("2024-10-07", result.get("checkOut"));
    }

    @Test
    void createBooking_confirmationCodeIsNonEmpty() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Frank", "DELUXE", "2024-11-01", "2024-11-05");

        // Assert
        String confirmCode = (String) result.get("confirmationCode");
        assertNotNull(confirmCode);
        assertFalse(confirmCode.isEmpty(), "Confirmation code should not be empty");
    }

    @Test
    void createBooking_jdbcTemplateUpdateCalledOnce() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Grace", "SUITE", "2024-12-01", "2024-12-05");

        // Assert
        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // getBookingById tests
    // -------------------------------------------------------------------------

    @Test
    void getBookingById_whenBookingExists_returnsBookingDetails() {
        // Arrange
        Map<String, Object> mockRow = new HashMap<>();
        mockRow.put("id", "BK-12345678");
        mockRow.put("guest", "Henry");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-12345678"))).thenReturn(mockRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-12345678");

        // Assert
        assertNotNull(result);
        assertEquals("BK-12345678", result.get("id"));
        assertEquals("Henry", result.get("guest"));
    }

    @Test
    void getBookingById_whenBookingNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-NOTFOUND")))
                .thenThrow(new RuntimeException("No results"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTFOUND");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        String errorMsg = (String) result.get("error");
        assertTrue(errorMsg.contains("BK-NOTFOUND"), "Error message should contain the booking ID");
    }

    @Test
    void getBookingById_errorMessageContainsBookingNotFoundPrefix() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), anyString()))
                .thenThrow(new RuntimeException("EmptyResultDataAccessException"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-UNKNOWN");

        // Assert
        String error = (String) result.get("error");
        assertTrue(error.startsWith("Booking not found:"));
    }

    // -------------------------------------------------------------------------
    // calculateRoomPrice tests — STANDARD room
    // -------------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoomNoPeakNoLoyalty_returnsBasePrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomNoPeakNoLoyalty_returnsBasePrice() {
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomNoPeakNoLoyalty_returnsBasePrice() {
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomNoPeakNoLoyalty_returnsBasePrice() {
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_usesDefaultBasePrice() {
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    // -------------------------------------------------------------------------
    // calculateRoomPrice — season multiplier tests
    // -------------------------------------------------------------------------

    @Test
    void calculateRoomPrice_peakSeason_appliesOnePointFiveMultiplier() {
        // 120.0 * 1.5 * 1 night = 180.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_offSeason_appliesZeroPointEightMultiplier() {
        // 120.0 * 0.8 * 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_defaultSeason_noMultiplierApplied() {
        // 120.0 * 1.0 * 1 night = 120.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "REGULAR", "NONE");
        assertEquals("120.00", price);
    }

    // -------------------------------------------------------------------------
    // calculateRoomPrice — loyalty discount tests
    // -------------------------------------------------------------------------

    @Test
    void calculateRoomPrice_goldLoyalty_appliesTenPercentDiscount() {
        // 120.0 * 0.9 * 1 = 108.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyalty_appliesTwentyPercentDiscount() {
        // 120.0 * 0.8 * 1 = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_appliesThirtyPercentDiscount() {
        // 120.0 * 0.7 * 1 = 84.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_noLoyalty_noDiscountApplied() {
        // 120.0 * 1.0 * 1 = 120.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "REGULAR");
        assertEquals("120.00", price);
    }

    // -------------------------------------------------------------------------
    // calculateRoomPrice — length-of-stay discount tests
    // -------------------------------------------------------------------------

    @Test
    void calculateRoomPrice_sevenNights_appliesFivePercentLengthDiscount() {
        // 120.0 * 0.95 * 7 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_appliesTenPercentLengthDiscount() {
        // 120.0 * 0.90 * 14 = 1512.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_sixNights_noLengthDiscount() {
        // 120.0 * 6 = 720.00
        String price = bookingService.calculateRoomPrice("STANDARD", 6, "NORMAL", "NONE");
        assertEquals("720.00", price);
    }

    @Test
    void calculateRoomPrice_fifteenNights_appliesTenPercentLengthDiscount() {
        // 120.0 * 0.90 * 15 = 1620.00
        String price = bookingService.calculateRoomPrice("STANDARD", 15, "NORMAL", "NONE");
        assertEquals("1620.00", price);
    }

    // -------------------------------------------------------------------------
    // calculateRoomPrice — combined multipliers
    // -------------------------------------------------------------------------

    @Test
    void calculateRoomPrice_suiteGoldPeakSevenNights_combinedMultipliers() {
        // 350 * 1.5 (PEAK) * 0.9 (GOLD) * 0.95 (7 nights) * 7 = 3142.125 -> "3142.13"
        String price = bookingService.calculateRoomPrice("SUITE", 7, "PEAK", "GOLD");
        assertEquals("3142.13", price);
    }

    @Test
    void calculateRoomPrice_villaDiamondOffFourteenNights_combinedMultipliers() {
        // 600 * 0.8 (OFF) * 0.7 (DIAMOND) * 0.90 (14 nights) * 14 = 4233.60
        String price = bookingService.calculateRoomPrice("VILLA", 14, "OFF", "DIAMOND");
        assertEquals("4233.60", price);
    }

    // -------------------------------------------------------------------------
    // isRoomAvailable tests
    // -------------------------------------------------------------------------

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
    void isRoomAvailable_emptyString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_lowercaseRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("standard"));
    }

    // -------------------------------------------------------------------------
    // generateReport tests
    // -------------------------------------------------------------------------

    @Test
    void generateReport_returnsNonNullString() {
        String result = bookingService.generateReport("2024-03");
        assertNotNull(result);
    }

    @Test
    void generateReport_containsMonthInMessage() {
        String result = bookingService.generateReport("2024-03");
        assertTrue(result.contains("2024-03"), "Report message should contain the month");
    }

    @Test
    void generateReport_containsTriggeredKeyword() {
        String result = bookingService.generateReport("2024-06");
        assertTrue(result.contains("triggered"), "Report message should contain 'triggered'");
    }

    @Test
    void generateReport_differentMonths_returnsDistinctMessages() {
        String result1 = bookingService.generateReport("2024-01");
        String result2 = bookingService.generateReport("2024-12");
        assertNotEquals(result1, result2);
    }
}
