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
    void createBooking_withValidParams_returnsBookingMap() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("bookingId"));
        assertEquals("John Smith", result.get("guestName"));
        assertEquals("SUITE", result.get("roomType"));
        assertEquals("2024-03-01", result.get("checkIn"));
        assertEquals("2024-03-05", result.get("checkOut"));
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
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with 'BK-'");
    }

    @Test
    void createBooking_confirmationCodeIsSha256Hex() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "VILLA", "2024-05-01", "2024-05-10");

        // Assert
        String confirmCode = (String) result.get("confirmationCode");
        assertNotNull(confirmCode);
        // SHA-256 hex is always 64 characters
        assertEquals(64, confirmCode.length(), "SHA-256 hex digest should be 64 chars");
        assertTrue(confirmCode.matches("[0-9a-f]+"), "Confirmation code should be lowercase hex");
    }

    @Test
    void createBooking_callsJdbcTemplateUpdate() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Bob", "STANDARD", "2024-06-01", "2024-06-02");

        // Assert
        verify(jdbcTemplate, times(1)).update(
                eq("INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)"),
                anyString(), eq("Bob"), eq("STANDARD"), eq("2024-06-01"), eq("2024-06-02"));
    }

    @Test
    void createBooking_eachCallGeneratesUniqueBookingId() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> booking1 = bookingService.createBooking("A", "SUITE", "2024-01-01", "2024-01-02");
        Map<String, Object> booking2 = bookingService.createBooking("B", "SUITE", "2024-01-01", "2024-01-02");

        // Assert
        assertNotEquals(booking1.get("bookingId"), booking2.get("bookingId"),
                "Each booking should have a unique ID");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingById tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingById_withValidId_returnsBookingData() {
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
        when(jdbcTemplate.queryForMap(anyString(), anyString())).thenReturn(new HashMap<>());

        // Act
        bookingService.getBookingById("BK-ABCDEF12");

        // Assert
        verify(jdbcTemplate).queryForMap(
                eq("SELECT * FROM bookings WHERE id = ?"),
                eq("BK-ABCDEF12"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateRoomPrice tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void calculateRoomPrice_standardRoomNormalSeasonNoLoyalty_correctPrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "NORMAL", "NONE");

        // Assert: 120.0 * 3 = 360.00
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomPeakSeasonNoLoyalty_correctPrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("DELUXE", 2, "PEAK", "NONE");

        // Assert: 200.0 * 1.5 * 2 = 600.00
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomOffSeasonNoLoyalty_correctPrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("SUITE", 4, "OFF", "NONE");

        // Assert: 350.0 * 0.8 * 4 = 1120.00
        assertEquals("1120.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomNormalSeasonGoldLoyalty_correctPrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "GOLD");

        // Assert: 600.0 * 0.9 * 1 = 540.00
        assertEquals("540.00", price);
    }

    @Test
    void calculateRoomPrice_standardRoomNormalSeasonPlatinumLoyalty_correctPrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");

        // Assert: 120.0 * 0.8 * 1 = 96.00
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_standardRoomNormalSeasonDiamondLoyalty_correctPrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");

        // Assert: 120.0 * 0.7 * 1 = 84.00
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNightsAppliesFivePercentDiscount() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");

        // Assert: 120.0 * 0.95 * 7 = 798.00
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNightsAppliesTenPercentDiscount() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");

        // Assert: 120.0 * 0.90 * 14 = 1512.00
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomTypeDefaultsToStandardPrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");

        // Assert: default 120.0 * 1 = 120.00
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonWithDiamondLoyaltyAndLongStay() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("VILLA", 14, "PEAK", "DIAMOND");

        // Assert: 600.0 * 1.5 * 0.7 * 0.90 * 14 = 7938.00
        assertEquals("7938.00", price);
    }

    @ParameterizedTest
    @CsvSource({
        "STANDARD, 120.0",
        "DELUXE,   200.0",
        "SUITE,    350.0",
        "VILLA,    600.0"
    })
    void calculateRoomPrice_allRoomTypes_correctBasePrice(String roomType, double expectedBase) {
        // Act
        String price = bookingService.calculateRoomPrice(roomType, 1, "NORMAL", "NONE");

        // Assert
        double actual = Double.parseDouble(price);
        assertEquals(expectedBase, actual, 0.001);
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
    void isRoomAvailable_nullRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(null));
    }

    @Test
    void isRoomAvailable_emptyString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "DELUXE", "SUITE", "VILLA"})
    void isRoomAvailable_allValidRoomTypes_returnTrue(String roomType) {
        assertTrue(bookingService.isRoomAvailable(roomType));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateReport_withValidMonth_returnsReportMessage() {
        // Act
        String result = bookingService.generateReport("March");

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("March"), "Report message should contain the month");
    }

    @Test
    void generateReport_messageContainsExpectedPrefix() {
        // Act
        String result = bookingService.generateReport("2024-03");

        // Assert
        assertTrue(result.startsWith("Report generation triggered for:"),
                "Message should start with expected prefix");
    }

    @Test
    void generateReport_withEmptyMonth_returnsMessageWithEmptyMonth() {
        // Act
        String result = bookingService.generateReport("");

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }
}
