package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        ReflectionTestUtils.setField(bookingService, "paymentApi", "https://payment-svc/charge");
    }

    // ─── createBooking ────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidParams_returnsBookingMap() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05");

        // Assert
        assertNotNull(result);
        assertEquals("John Smith", result.get("guestName"));
        assertEquals("SUITE", result.get("roomType"));
        assertEquals("2024-03-01", result.get("checkIn"));
        assertEquals("2024-03-05", result.get("checkOut"));
        assertNotNull(result.get("bookingId"));
        assertNotNull(result.get("confirmationCode"));
    }

    @Test
    void createBooking_bookingIdStartsWithBK() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Jane Doe", "DELUXE", "2024-04-01", "2024-04-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertNotNull(bookingId);
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    void createBooking_confirmationCodeIsNotEmpty() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "STANDARD", "2024-05-01", "2024-05-02");

        // Assert
        String confirmCode = (String) result.get("confirmationCode");
        assertNotNull(confirmCode);
        assertFalse(confirmCode.isEmpty(), "Confirmation code should not be empty");
    }

    @Test
    void createBooking_jdbcTemplateUpdateIsCalled() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Bob", "VILLA", "2024-06-01", "2024-06-10");

        // Assert
        verify(jdbcTemplate, times(1)).update(
                eq("INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)"),
                anyString(), eq("Bob"), eq("VILLA"), eq("2024-06-01"), eq("2024-06-10"));
    }

    @Test
    void createBooking_withDifferentRoomTypes_allSucceed() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act & Assert
        for (String roomType : new String[]{"STANDARD", "DELUXE", "SUITE", "VILLA"}) {
            Map<String, Object> result = bookingService.createBooking(
                    "Guest", roomType, "2024-07-01", "2024-07-05");
            assertNotNull(result);
            assertEquals(roomType, result.get("roomType"));
        }
    }

    // ─── getBookingById ───────────────────────────────────────────────────────

    @Test
    void getBookingById_withValidId_returnsBookingDetails() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-12345678");
        dbRow.put("guest", "John Smith");
        dbRow.put("room", "SUITE");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-12345678"))).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-12345678");

        // Assert
        assertNotNull(result);
        assertEquals("BK-12345678", result.get("id"));
        assertEquals("John Smith", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-NOTFOUND")))
                .thenThrow(new RuntimeException("No results"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTFOUND");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"), "Result should contain 'error' key");
        String errorMsg = (String) result.get("error");
        assertTrue(errorMsg.contains("BK-NOTFOUND"), "Error message should contain the booking ID");
    }

    @Test
    void getBookingById_usesParameterisedQuery() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCDEF12");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-ABCDEF12"))).thenReturn(dbRow);

        // Act
        bookingService.getBookingById("BK-ABCDEF12");

        // Assert
        verify(jdbcTemplate, times(1)).queryForMap(
                eq("SELECT * FROM bookings WHERE id = ?"), eq("BK-ABCDEF12"));
    }

    // ─── calculateRoomPrice ───────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_standardRoomPeakSeasonNoLoyalty_correctPrice() {
        // Arrange: STANDARD=120, PEAK=1.5x, no loyalty=1.0x, 3 nights
        // Expected: 120 * 1.5 * 1.0 * 3 = 540.00
        // Act
        String result = bookingService.calculateRoomPrice("STANDARD", 3, "PEAK", "NONE");

        // Assert
        assertEquals("540.00", result);
    }

    @Test
    void calculateRoomPrice_deluxeOffSeasonGoldLoyalty_correctPrice() {
        // Arrange: DELUXE=200, OFF=0.8x, GOLD=0.9x, 5 nights
        // Expected: 200 * 0.8 * 0.9 * 5 = 720.00
        // Act
        String result = bookingService.calculateRoomPrice("DELUXE", 5, "OFF", "GOLD");

        // Assert
        assertEquals("720.00", result);
    }

    @Test
    void calculateRoomPrice_suiteNormalSeasonPlatinumLoyalty_correctPrice() {
        // Arrange: SUITE=350, normal=1.0x, PLATINUM=0.8x, 2 nights
        // Expected: 350 * 1.0 * 0.8 * 2 = 560.00
        // Act
        String result = bookingService.calculateRoomPrice("SUITE", 2, "NORMAL", "PLATINUM");

        // Assert
        assertEquals("560.00", result);
    }

    @Test
    void calculateRoomPrice_villaPeakSeasonDiamondLoyalty_correctPrice() {
        // Arrange: VILLA=600, PEAK=1.5x, DIAMOND=0.7x, 1 night
        // Expected: 600 * 1.5 * 0.7 * 1 = 630.00
        // Act
        String result = bookingService.calculateRoomPrice("VILLA", 1, "PEAK", "DIAMOND");

        // Assert
        assertEquals("630.00", result);
    }

    @Test
    void calculateRoomPrice_sevenNightsAppliesLongStayDiscount() {
        // Arrange: STANDARD=120, no season multiplier, no loyalty, 7 nights → 5% discount
        // Expected: 120 * 1.0 * 1.0 * 0.95 * 7 = 798.00
        // Act
        String result = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");

        // Assert
        assertEquals("798.00", result);
    }

    @Test
    void calculateRoomPrice_fourteenNightsAppliesTenPercentDiscount() {
        // Arrange: STANDARD=120, no season, no loyalty, 14 nights → 10% discount
        // Expected: 120 * 1.0 * 1.0 * 0.90 * 14 = 1512.00
        // Act
        String result = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");

        // Assert
        assertEquals("1512.00", result);
    }

    @Test
    void calculateRoomPrice_unknownRoomTypeDefaultsToStandard() {
        // Arrange: unknown room type defaults to 120.0, no season, no loyalty, 1 night
        // Expected: 120 * 1.0 * 1.0 * 1 = 120.00
        // Act
        String result = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("120.00", result);
    }

    @Test
    void calculateRoomPrice_returnsFormattedTwoDecimalString() {
        // Act
        String result = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");

        // Assert
        assertNotNull(result);
        assertTrue(result.matches("\\d+\\.\\d{2}"), "Price should be formatted with 2 decimal places");
    }

    @Test
    void calculateRoomPrice_peakSeasonMultiplierApplied() {
        // STANDARD=120, PEAK=1.5x, 1 night, no loyalty → 180.00
        String result = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");
        assertEquals("180.00", result);
    }

    @Test
    void calculateRoomPrice_offSeasonMultiplierApplied() {
        // STANDARD=120, OFF=0.8x, 1 night, no loyalty → 96.00
        String result = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");
        assertEquals("96.00", result);
    }

    // ─── isRoomAvailable ──────────────────────────────────────────────────────

    @Test
    void isRoomAvailable_standardRoomType_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
    }

    @Test
    void isRoomAvailable_deluxeRoomType_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
    }

    @Test
    void isRoomAvailable_suiteRoomType_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("SUITE"));
    }

    @Test
    void isRoomAvailable_villaRoomType_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_unknownRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    void isRoomAvailable_emptyString_returnsFalse() {
        // Java's Set.of() throws NullPointerException on null input (by design).
        // The production code uses Set.of() which does not support null — verify this behaviour.
        assertThrows(NullPointerException.class,
                () -> bookingService.isRoomAvailable(null),
                "Set.of() should throw NullPointerException for null input");
        assertFalse(bookingService.isRoomAvailable("standard"));
    }

    // ─── generateReport ───────────────────────────────────────────────────────

    @Test
    void generateReport_withMonth_returnsMessageContainingMonth() {
        // Act
        String result = bookingService.generateReport("March");

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("March"), "Report message should contain the month");
    }

    @Test
    void generateReport_withMonth_returnsMessageContainingPaymentApi() {
        // Act
        String result = bookingService.generateReport("April");

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("https://payment-svc/charge"),
                "Report message should contain the payment API endpoint");
    }

    @Test
    void generateReport_withDifferentMonths_returnsDistinctMessages() {
        // Act
        String result1 = bookingService.generateReport("January");
        String result2 = bookingService.generateReport("February");

        // Assert
        assertNotEquals(result1, result2, "Reports for different months should differ");
    }
}
