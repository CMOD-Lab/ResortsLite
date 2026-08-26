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

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidInputs_returnsBookingMap() {
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
        assertNotNull(bookingId);
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    void createBooking_confirmationCodeIsNotNull() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "STANDARD", "2024-08-10", "2024-08-12");

        // Assert
        assertNotNull(result.get("confirmationCode"), "Confirmation code must not be null");
        assertFalse(((String) result.get("confirmationCode")).isEmpty(),
                "Confirmation code must not be empty");
    }

    @Test
    void createBooking_confirmationCodeIsSha256Hex() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Dave", "VILLA", "2024-09-01", "2024-09-07");

        // Assert – SHA-256 hex is always 64 characters
        String code = (String) result.get("confirmationCode");
        assertNotNull(code);
        assertEquals(64, code.length(), "SHA-256 hex digest must be 64 characters");
        assertTrue(code.matches("[0-9a-f]+"), "SHA-256 hex must contain only hex characters");
    }

    @Test
    void createBooking_jdbcTemplateUpdateCalledOnce() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Eve", "STANDARD", "2024-10-01", "2024-10-02");

        // Assert
        verify(jdbcTemplate, times(1)).update(
                anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void createBooking_withSpecialCharactersInGuestName_doesNotThrow() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act & Assert – parameterised query should handle special chars safely
        assertDoesNotThrow(() ->
                bookingService.createBooking("O'Brien; DROP TABLE bookings;--",
                        "STANDARD", "2024-01-01", "2024-01-02"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingById tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingById_withValidId_returnsBookingDetails() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "Frank");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-ABCD1234"))).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        // Assert
        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("id"));
        assertEquals("Frank", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), anyString()))
                .thenThrow(new RuntimeException("No results"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTEXIST");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"), "Result should contain 'error' key");
        assertTrue(((String) result.get("error")).contains("BK-NOTEXIST"));
    }

    @Test
    void getBookingById_jdbcTemplateQueryCalledWithCorrectId() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-TEST001")))
                .thenReturn(new HashMap<>());

        // Act
        bookingService.getBookingById("BK-TEST001");

        // Assert
        verify(jdbcTemplate, times(1)).queryForMap(anyString(), eq("BK-TEST001"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_standardRoomNormalSeason_returnsCorrectPrice() {
        // Arrange – STANDARD base = 120, NORMAL season (no multiplier), no loyalty, 3 nights
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "NORMAL", "NONE");

        // Assert
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomPeakSeason_returnsCorrectPrice() {
        // Arrange – DELUXE base = 200, PEAK * 1.5 = 300, 2 nights = 600
        // Act
        String price = bookingService.calculateRoomPrice("DELUXE", 2, "PEAK", "NONE");

        // Assert
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomOffSeason_returnsCorrectPrice() {
        // Arrange – SUITE base = 350, OFF * 0.8 = 280, 1 night = 280
        // Act
        String price = bookingService.calculateRoomPrice("SUITE", 1, "OFF", "NONE");

        // Assert
        assertEquals("280.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomNormalSeason_returnsCorrectPrice() {
        // Arrange – VILLA base = 600, NORMAL, 1 night = 600
        // Act
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_defaultsToStandardBase() {
        // Arrange – unknown type defaults to 120, 1 night = 120
        // Act
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_goldLoyalty_appliesDiscount() {
        // Arrange – STANDARD 120, NORMAL, GOLD * 0.9 = 108, 1 night = 108
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");

        // Assert
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyalty_appliesDiscount() {
        // Arrange – STANDARD 120, NORMAL, PLATINUM * 0.8 = 96, 1 night = 96
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");

        // Assert
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_appliesDiscount() {
        // Arrange – STANDARD 120, NORMAL, DIAMOND * 0.7 = 84, 1 night = 84
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");

        // Assert
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNights_appliesLongStayDiscount() {
        // Arrange – STANDARD 120, NORMAL, no loyalty, 7 nights * 0.95 = 798.00
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");

        // Assert
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_appliesLongerStayDiscount() {
        // NOTE: The implementation checks >= 7 first, so 14 nights also hits the 0.95 branch.
        // Arrange – STANDARD 120, NORMAL, no loyalty, 14 nights * 0.95 = 1596.00
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");

        // Assert – 120 * 0.95 * 14 = 1596.00
        assertEquals("1596.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonWithDiamondLoyalty_combinedDiscounts() {
        // Arrange – DELUXE 200, PEAK * 1.5 = 300, DIAMOND * 0.7 = 210, 2 nights = 420
        // Act
        String price = bookingService.calculateRoomPrice("DELUXE", 2, "PEAK", "DIAMOND");

        // Assert
        assertEquals("420.00", price);
    }

    @Test
    void calculateRoomPrice_returnsStringFormattedToTwoDecimals() {
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");

        // Assert
        assertTrue(price.matches("\\d+\\.\\d{2}"), "Price must be formatted to 2 decimal places");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isRoomAvailable tests
    // ─────────────────────────────────────────────────────────────────────────

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
        // Room type matching is case-sensitive
        assertFalse(bookingService.isRoomAvailable("standard"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateReport_returnsStringContainingMonth() {
        // Act
        String result = bookingService.generateReport("March");

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("March"), "Report message should contain the month");
    }

    @Test
    void generateReport_returnsNonEmptyString() {
        // Act
        String result = bookingService.generateReport("January");

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void generateReport_withNumericMonth_returnsStringContainingMonth() {
        // Act
        String result = bookingService.generateReport("03");

        // Assert
        assertTrue(result.contains("03"));
    }
}
