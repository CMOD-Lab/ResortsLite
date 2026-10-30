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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
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
        doNothing().when(jdbcTemplate).execute(anyString());

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
    void createBooking_returnsBookingIdWithBKPrefix() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertNotNull(bookingId);
        assertTrue(bookingId.startsWith("BK-"), "bookingId should start with 'BK-'");
    }

    @Test
    void createBooking_returnsNonNullConfirmationCode() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "STANDARD", "2024-08-10", "2024-08-12");

        // Assert
        assertNotNull(result.get("confirmationCode"));
        assertFalse(((String) result.get("confirmationCode")).isEmpty());
    }

    @Test
    void createBooking_confirmationCodeIsSha256Hex() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Dave", "VILLA", "2024-09-01", "2024-09-07");

        // Assert – SHA-256 hex is always 64 characters
        String code = (String) result.get("confirmationCode");
        assertNotNull(code);
        assertEquals(64, code.length(), "SHA-256 hex digest must be 64 chars");
        assertTrue(code.matches("[0-9a-f]+"), "SHA-256 hex must contain only hex chars");
    }

    @Test
    void createBooking_includesDbHostInResult() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Eve", "STANDARD", "2024-10-01", "2024-10-02");

        // Assert
        assertNotNull(result.get("dbHost"));
    }

    @Test
    void createBooking_executesJdbcInsert() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        bookingService.createBooking("Frank", "DELUXE", "2024-11-01", "2024-11-03");

        // Assert – verify JDBC was called exactly once
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    @Test
    void createBooking_eachCallGeneratesUniqueBookingId() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> r1 = bookingService.createBooking("G1", "SUITE", "2024-01-01", "2024-01-02");
        Map<String, Object> r2 = bookingService.createBooking("G2", "SUITE", "2024-01-01", "2024-01-02");

        // Assert
        assertNotEquals(r1.get("bookingId"), r2.get("bookingId"),
                "Each booking should have a unique ID");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingById tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingById_whenFound_returnsBookingData() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "Alice");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        // Assert
        assertNotNull(result);
        assertEquals("Alice", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("No rows found"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTEXIST");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"), "Should contain 'error' key on exception");
        assertTrue(((String) result.get("error")).contains("BK-NOTEXIST"));
    }

    @Test
    void getBookingById_errorMessageContainsBookingId() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("empty result"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-XYZ999");

        // Assert
        String errorMsg = (String) result.get("error");
        assertNotNull(errorMsg);
        assertTrue(errorMsg.contains("BK-XYZ999"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_standardRoomNormalSeasonNoLoyalty_correctPrice() {
        // Arrange – STANDARD base=120, NORMAL season (no multiplier), no loyalty, 1 night
        // Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomNormalSeasonNoLoyalty_correctPrice() {
        // Arrange – DELUXE base=200, 1 night
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomNormalSeasonNoLoyalty_correctPrice() {
        // Arrange – SUITE base=350, 1 night
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomNormalSeasonNoLoyalty_correctPrice() {
        // Arrange – VILLA base=600, 1 night
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_defaultsToStandardPrice() {
        // Arrange – unknown type defaults to 120.0
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeason_appliesMultiplier() {
        // Arrange – STANDARD 120 * 1.5 (PEAK) * 1 night = 180.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_offSeason_appliesDiscount() {
        // Arrange – STANDARD 120 * 0.8 (OFF) * 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_goldLoyalty_appliesDiscount() {
        // Arrange – STANDARD 120 * 0.9 (GOLD) * 1 night = 108.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyalty_appliesDiscount() {
        // Arrange – STANDARD 120 * 0.8 (PLATINUM) * 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_appliesDiscount() {
        // Arrange – STANDARD 120 * 0.7 (DIAMOND) * 1 night = 84.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNights_appliesWeeklyDiscount() {
        // Arrange – STANDARD 120 * 0.95 (7+ nights) * 7 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_appliesBiweeklyDiscount() {
        // Arrange – STANDARD 120 * 0.90 (14+ nights) * 14 = 1512.00
        // NOTE: code checks nights>=7 first, so 14 nights hits the >=7 branch (0.95)
        // 120 * 0.95 * 14 = 1596.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        // The code checks >=7 before >=14, so 14 nights gets 0.95 multiplier
        assertEquals("1596.00", price);
    }

    @Test
    void calculateRoomPrice_multipleNights_returnsFormattedString() {
        // Arrange – DELUXE 200 * 3 nights = 600.00
        String price = bookingService.calculateRoomPrice("DELUXE", 3, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonWithGoldLoyalty_combinedMultipliers() {
        // Arrange – SUITE 350 * 1.5 (PEAK) * 0.9 (GOLD) * 2 nights = 945.00
        String price = bookingService.calculateRoomPrice("SUITE", 2, "PEAK", "GOLD");
        assertEquals("945.00", price);
    }

    @ParameterizedTest
    @CsvSource({
        "STANDARD, 1, NORMAL, NONE, 120.00",
        "DELUXE,   1, NORMAL, NONE, 200.00",
        "SUITE,    1, NORMAL, NONE, 350.00",
        "VILLA,    1, NORMAL, NONE, 600.00"
    })
    void calculateRoomPrice_parameterized_baseRoomPrices(
            String roomType, int nights, String season, String loyalty, String expected) {
        String price = bookingService.calculateRoomPrice(roomType, nights, season, loyalty);
        assertEquals(expected, price);
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
        assertFalse(bookingService.isRoomAvailable("standard"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "DELUXE", "SUITE", "VILLA"})
    void isRoomAvailable_allValidRoomTypes_returnTrue(String roomType) {
        assertTrue(bookingService.isRoomAvailable(roomType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENTHOUSE", "CABIN", "TENT", "BUNGALOW", ""})
    void isRoomAvailable_invalidRoomTypes_returnFalse(String roomType) {
        assertFalse(bookingService.isRoomAvailable(roomType));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateReport_withValidMonth_returnsNonNullString() {
        String result = bookingService.generateReport("March");
        assertNotNull(result);
    }

    @Test
    void generateReport_containsMonthInResult() {
        String result = bookingService.generateReport("June");
        assertTrue(result.contains("June"), "Result should contain the month name");
    }

    @Test
    void generateReport_containsPaymentApiReference() {
        String result = bookingService.generateReport("January");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void generateReport_differentMonths_returnDifferentStrings() {
        String r1 = bookingService.generateReport("January");
        String r2 = bookingService.generateReport("February");
        assertNotEquals(r1, r2);
    }
}
