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
        ReflectionTestUtils.setField(bookingService, "paymentApi",
                "http://payment-svc.internal:9090/charge");
    }

    // -----------------------------------------------------------------------
    // createBooking tests
    // -----------------------------------------------------------------------

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
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with BK-");
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
        assertFalse(confirmCode.isEmpty());
    }

    @Test
    void createBooking_callsJdbcTemplateUpdate() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        bookingService.createBooking("Bob", "VILLA", "2024-06-01", "2024-06-07");

        // Assert
        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void createBooking_withStandardRoom_returnsCorrectRoomType() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "STANDARD", "2024-07-01", "2024-07-03");

        // Assert
        assertEquals("STANDARD", result.get("roomType"));
    }

    // -----------------------------------------------------------------------
    // getBookingById tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingById_withValidId_returnsBookingDetails() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-12345678");
        dbRow.put("guest", "John Smith");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-12345678"))).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-12345678");

        // Assert
        assertNotNull(result);
        assertEquals("BK-12345678", result.get("id"));
        assertEquals("John Smith", result.get("guest"));
    }

    @Test
    void getBookingById_withInvalidId_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("INVALID-ID")))
                .thenThrow(new RuntimeException("No results found"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("INVALID-ID");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        String error = (String) result.get("error");
        assertTrue(error.contains("INVALID-ID"));
    }

    @Test
    void getBookingById_withNullId_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), (Object) isNull()))
                .thenThrow(new RuntimeException("Null id"));

        // Act
        Map<String, Object> result = bookingService.getBookingById(null);

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice tests
    // -----------------------------------------------------------------------

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
    void calculateRoomPrice_unknownRoomType_defaultsToStandardPrice() {
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

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
    void calculateRoomPrice_goldLoyalty_appliesDiscount() {
        // STANDARD 120 * 0.9 = 108 * 1 night = 108.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyalty_appliesDiscount() {
        // STANDARD 120 * 0.8 = 96 * 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_appliesDiscount() {
        // STANDARD 120 * 0.7 = 84 * 1 night = 84.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNights_appliesLengthOfStayDiscount() {
        // STANDARD 120 * 0.95 * 7 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_appliesLargerLengthOfStayDiscount() {
        // STANDARD 120 * 0.90 * 14 = 1512.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_multipleNightsNoDiscount_returnsCorrectTotal() {
        // STANDARD 120 * 3 = 360.00
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "NORMAL", "NONE");
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonGoldLoyalty_combinesMultipliers() {
        // STANDARD 120 * 1.5 * 0.9 = 162 * 1 = 162.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "GOLD");
        assertEquals("162.00", price);
    }

    @Test
    void calculateRoomPrice_villaSevenNightsPeak_returnsCorrectTotal() {
        // VILLA 600 * 1.5 * 0.95 * 7 = 5985.00
        String price = bookingService.calculateRoomPrice("VILLA", 7, "PEAK", "NONE");
        assertEquals("5985.00", price);
    }

    // -----------------------------------------------------------------------
    // isRoomAvailable tests
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
    void isRoomAvailable_unknownRoom_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    void isRoomAvailable_emptyString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_nullRoom_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(null));
    }

    @Test
    void isRoomAvailable_lowercaseRoom_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("standard"));
    }

    // -----------------------------------------------------------------------
    // generateReport tests
    // -----------------------------------------------------------------------

    @Test
    void generateReport_withValidMonth_returnsStatusMessage() {
        String result = bookingService.generateReport("March");
        assertNotNull(result);
        assertTrue(result.contains("March"));
    }

    @Test
    void generateReport_containsPaymentApiReference() {
        String result = bookingService.generateReport("April");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void generateReport_withEmptyMonth_returnsMessage() {
        String result = bookingService.generateReport("");
        assertNotNull(result);
    }
}
