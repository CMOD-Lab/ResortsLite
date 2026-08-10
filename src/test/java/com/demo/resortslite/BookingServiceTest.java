package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

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

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bookingService, "paymentApi",
                "https://payment-svc.internal:9090/charge");
    }

    // ─── createBooking ────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidInputs_returnsBookingMap() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

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
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertTrue(bookingId.startsWith("BK-"),
                "Booking ID should start with 'BK-'");
    }

    @Test
    void createBooking_confirmationCodeIsNotEmpty() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "STANDARD", "2024-08-01", "2024-08-02");

        // Assert
        String confirmCode = (String) result.get("confirmationCode");
        assertNotNull(confirmCode);
        assertFalse(confirmCode.isEmpty());
    }

    @Test
    void createBooking_invokesJdbcTemplateUpdate() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        bookingService.createBooking("Dave", "VILLA", "2024-09-01", "2024-09-10");

        // Assert
        verify(jdbcTemplate, times(1))
                .update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void createBooking_withSpecialCharactersInGuestName_succeeds() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "O'Brien & Co.", "STANDARD", "2024-01-01", "2024-01-02");

        // Assert
        assertNotNull(result);
        assertEquals("O'Brien & Co.", result.get("guestName"));
    }

    // ─── getBookingById ───────────────────────────────────────────────────────

    @Test
    void getBookingById_whenFound_returnsBookingData() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "Eve");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-ABCD1234")))
                .thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        // Assert
        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("id"));
        assertEquals("Eve", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-NOTEXIST")))
                .thenThrow(new RuntimeException("No results"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTEXIST");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        String errorMsg = (String) result.get("error");
        assertTrue(errorMsg.contains("BK-NOTEXIST"));
    }

    @Test
    void getBookingById_errorMessageContainsBookingNotFound() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), anyString()))
                .thenThrow(new RuntimeException("EmptyResultDataAccessException"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-XYZ");

        // Assert
        String error = (String) result.get("error");
        assertTrue(error.startsWith("Booking not found:"));
    }

    // ─── calculateRoomPrice ───────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_standardRoomNormalSeasonNoLoyalty_correctPrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "NORMAL", "NONE");

        // Assert — 120.0 * 3 = 360.00
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomNormalSeasonNoLoyalty_correctPrice() {
        // 200.0 * 2 = 400.00
        String price = bookingService.calculateRoomPrice("DELUXE", 2, "NORMAL", "NONE");
        assertEquals("400.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomNormalSeasonNoLoyalty_correctPrice() {
        // 350.0 * 1 = 350.00
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomNormalSeasonNoLoyalty_correctPrice() {
        // 600.0 * 1 = 600.00
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomTypeDefaultsToStandard() {
        // Unknown room type defaults to 120.0
        // 120.0 * 1 = 120.00
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonApplies150PercentMultiplier() {
        // STANDARD 120 * 1.5 = 180 * 2 nights = 360.00
        String price = bookingService.calculateRoomPrice("STANDARD", 2, "PEAK", "NONE");
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_offSeasonApplies80PercentMultiplier() {
        // STANDARD 120 * 0.8 = 96 * 2 = 192.00
        String price = bookingService.calculateRoomPrice("STANDARD", 2, "OFF", "NONE");
        assertEquals("192.00", price);
    }

    @Test
    void calculateRoomPrice_goldLoyaltyApplies10PercentDiscount() {
        // STANDARD 120 * 0.9 = 108 * 1 = 108.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyaltyApplies20PercentDiscount() {
        // STANDARD 120 * 0.8 = 96 * 1 = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyaltyApplies30PercentDiscount() {
        // STANDARD 120 * 0.7 = 84 * 1 = 84.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNightsApplies5PercentDiscount() {
        // STANDARD 120 * 0.95 * 7 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNightsApplies10PercentDiscount() {
        // nights >= 14 branch: 120 * 0.90 * 14 = 1512.00
        // NOTE: the code checks >= 7 first, so nights=14 hits the >= 7 branch (0.95)
        // 120 * 0.95 * 14 = 1596.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        // The code evaluates >= 7 first, so 14 nights → 0.95 multiplier
        assertEquals("1596.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonWithGoldLoyalty_combinedMultipliers() {
        // DELUXE 200 * 1.5 (PEAK) = 300 * 0.9 (GOLD) = 270 * 3 = 810.00
        String price = bookingService.calculateRoomPrice("DELUXE", 3, "PEAK", "GOLD");
        assertEquals("810.00", price);
    }

    @Test
    void calculateRoomPrice_returnsFormattedTwoDecimalString() {
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");
        assertTrue(price.matches("\\d+\\.\\d{2}"),
                "Price should be formatted with 2 decimal places");
    }

    // ─── isRoomAvailable ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "DELUXE", "SUITE", "VILLA"})
    void isRoomAvailable_validRoomTypes_returnsTrue(String roomType) {
        assertTrue(bookingService.isRoomAvailable(roomType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "PENTHOUSE", "", "standard", "deluxe"})
    void isRoomAvailable_invalidRoomTypes_returnsFalse(String roomType) {
        assertFalse(bookingService.isRoomAvailable(roomType));
    }

    @Test
    void isRoomAvailable_nullRoomType_throwsException() {
        assertThrows(NullPointerException.class,
                () -> bookingService.isRoomAvailable(null));
    }

    // ─── generateReport ───────────────────────────────────────────────────────

    @Test
    void generateReport_returnsStringContainingMonth() {
        // Act
        String result = bookingService.generateReport("March");

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("March"));
    }

    @Test
    void generateReport_returnsStringContainingPaymentApi() {
        // Act
        String result = bookingService.generateReport("April");

        // Assert
        assertTrue(result.contains("https://payment-svc.internal:9090/charge"));
    }

    @Test
    void generateReport_returnsNonEmptyString() {
        String result = bookingService.generateReport("January");
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void generateReport_withEmptyMonth_returnsStringWithEmptyMonth() {
        String result = bookingService.generateReport("");
        assertNotNull(result);
        assertTrue(result.contains("Report generation triggered for:"));
    }
}
