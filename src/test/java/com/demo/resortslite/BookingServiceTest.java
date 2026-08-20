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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link BookingService}.
 * Covers createBooking, getBookingById, calculateRoomPrice, isRoomAvailable, generateReport.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidInputs_returnsMapWithBookingId() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice Smith", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("bookingId"));
        assertTrue(result.get("bookingId").toString().startsWith("BK-"));
    }

    @Test
    void createBooking_withValidInputs_returnsGuestName() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob Jones", "DELUXE", "2024-07-10", "2024-07-15");

        // Assert
        assertEquals("Bob Jones", result.get("guestName"));
    }

    @Test
    void createBooking_withValidInputs_returnsRoomType() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol White", "VILLA", "2024-08-01", "2024-08-10");

        // Assert
        assertEquals("VILLA", result.get("roomType"));
    }

    @Test
    void createBooking_withValidInputs_returnsCheckInAndCheckOut() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Dave Brown", "STANDARD", "2024-09-01", "2024-09-03");

        // Assert
        assertEquals("2024-09-01", result.get("checkIn"));
        assertEquals("2024-09-03", result.get("checkOut"));
    }

    @Test
    void createBooking_withValidInputs_returnsConfirmationCode() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Eve Green", "SUITE", "2024-10-01", "2024-10-07");

        // Assert
        assertNotNull(result.get("confirmationCode"));
        assertFalse(result.get("confirmationCode").toString().isEmpty());
    }

    @Test
    void createBooking_invokesJdbcTemplateUpdate() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Frank Lee", "DELUXE", "2024-11-01", "2024-11-04");

        // Assert
        verify(jdbcTemplate, times(1)).update(
                contains("INSERT INTO bookings"),
                any(), any(), any(), any(), any());
    }

    @Test
    void createBooking_usesParameterisedQuery_notStringConcatenation() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Injection'; DROP TABLE bookings;--", "SUITE",
                "2024-01-01", "2024-01-05");

        // Assert — jdbcTemplate.update called with placeholders, not raw SQL
        verify(jdbcTemplate).update(
                eq("INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)"),
                any(), any(), any(), any(), any());
    }

    @Test
    void createBooking_generatesUniqueBookingIds() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> booking1 = bookingService.createBooking("G1", "STANDARD", "2024-01-01", "2024-01-02");
        Map<String, Object> booking2 = bookingService.createBooking("G2", "DELUXE",   "2024-01-01", "2024-01-02");

        // Assert
        assertNotEquals(booking1.get("bookingId"), booking2.get("bookingId"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingById
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingById_whenFound_returnsBookingMap() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-12345678");
        dbRow.put("guest", "Alice Smith");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-12345678"))).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-12345678");

        // Assert
        assertNotNull(result);
        assertEquals("BK-12345678", result.get("id"));
        assertEquals("Alice Smith", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorEntry() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-UNKNOWN")))
                .thenThrow(new RuntimeException("No results"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-UNKNOWN");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("BK-UNKNOWN"));
    }

    @Test
    void getBookingById_usesParameterisedQuery() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), any())).thenReturn(new HashMap<>());

        // Act
        bookingService.getBookingById("BK-ABCDEF12");

        // Assert
        verify(jdbcTemplate).queryForMap(
                eq("SELECT * FROM bookings WHERE id = ?"),
                eq("BK-ABCDEF12"));
    }

    @Test
    void getBookingById_withExceptionThrown_errorMessageContainsBookingId() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-MISSING")))
                .thenThrow(new RuntimeException("empty result set"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-MISSING");

        // Assert
        assertTrue(result.get("error").toString().contains("BK-MISSING"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_standardRoomPeakSeasonNoLoyalty_returnsCorrectPrice() {
        // 120.0 * 1.5 * 1.0 * 3 = 540.00
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "PEAK", "NONE");
        assertEquals("540.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomOffSeasonGoldLoyalty_returnsCorrectPrice() {
        // 200.0 * 0.8 * 0.9 * 2 = 288.00
        String price = bookingService.calculateRoomPrice("DELUXE", 2, "OFF", "GOLD");
        assertEquals("288.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomStandardSeasonPlatinumLoyalty_returnsCorrectPrice() {
        // 350.0 * 1.0 * 0.8 * 4 = 1120.00
        String price = bookingService.calculateRoomPrice("SUITE", 4, "REGULAR", "PLATINUM");
        assertEquals("1120.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomPeakSeasonDiamondLoyalty_returnsCorrectPrice() {
        // 600.0 * 1.5 * 0.7 * 1 = 630.00
        String price = bookingService.calculateRoomPrice("VILLA", 1, "PEAK", "DIAMOND");
        assertEquals("630.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNights_appliesFivePercentDiscount() {
        // 120.0 * 1.0 * 1.0 * 0.95 * 7 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "REGULAR", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_appliesTenPercentDiscount() {
        // 120.0 * 1.0 * 1.0 * 0.90 * 14 = 1512.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "REGULAR", "NONE");
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_fifteenNights_appliesTenPercentDiscount() {
        // 120.0 * 1.0 * 1.0 * 0.90 * 15 = 1620.00
        String price = bookingService.calculateRoomPrice("STANDARD", 15, "REGULAR", "NONE");
        assertEquals("1620.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_defaultsToStandardBasePrice() {
        // 120.0 * 1.0 * 1.0 * 1 = 120.00
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "REGULAR", "NONE");
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_unknownSeason_defaultsToMultiplierOne() {
        // 200.0 * 1.0 * 1.0 * 1 = 200.00
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "UNKNOWN_SEASON", "NONE");
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_unknownLoyalty_defaultsToNoDiscount() {
        // 350.0 * 1.0 * 1.0 * 1 = 350.00
        String price = bookingService.calculateRoomPrice("SUITE", 1, "REGULAR", "BRONZE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_returnsFormattedTwoDecimalString() {
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "REGULAR", "NONE");
        assertTrue(price.matches("\\d+\\.\\d{2}"), "Price should be formatted to 2 decimal places");
    }

    @ParameterizedTest
    @CsvSource({
        "STANDARD, 120.0",
        "DELUXE,   200.0",
        "SUITE,    350.0",
        "VILLA,    600.0"
    })
    void calculateRoomPrice_allRoomTypes_useCorrectBasePrice(String roomType, double expectedBase) {
        // 1 night, no season multiplier, no loyalty discount
        String price = bookingService.calculateRoomPrice(roomType, 1, "REGULAR", "NONE");
        assertEquals(String.format("%.2f", expectedBase), price);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isRoomAvailable
    // ─────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "DELUXE", "SUITE", "VILLA"})
    void isRoomAvailable_withValidRoomType_returnsTrue(String roomType) {
        assertTrue(bookingService.isRoomAvailable(roomType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENTHOUSE", "CABIN", "TENT", "", "standard", "deluxe"})
    void isRoomAvailable_withInvalidRoomType_returnsFalse(String roomType) {
        assertFalse(bookingService.isRoomAvailable(roomType));
    }

    @Test
    void isRoomAvailable_withNullRoomType_throwsNullPointerException() {
        // Set.of() in Java 17 throws NullPointerException for null elements —
        // the production code propagates this; we verify the behaviour here.
        assertThrows(NullPointerException.class,
                () -> bookingService.isRoomAvailable(null));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateReport_withValidMonth_returnsNonNullMessage() {
        String result = bookingService.generateReport("March");
        assertNotNull(result);
    }

    @Test
    void generateReport_withValidMonth_containsMonthInMessage() {
        String result = bookingService.generateReport("April");
        assertTrue(result.contains("April"));
    }

    @Test
    void generateReport_withEmptyMonth_returnsMessageContainingTriggerText() {
        String result = bookingService.generateReport("");
        assertNotNull(result);
        assertTrue(result.contains("Report generation triggered for:"));
    }

    @Test
    void generateReport_withDifferentMonths_returnsDistinctMessages() {
        String jan = bookingService.generateReport("January");
        String feb = bookingService.generateReport("February");
        assertNotEquals(jan, feb);
    }
}
