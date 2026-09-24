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

/**
 * Comprehensive unit tests for {@link BookingService}.
 * Covers createBooking, getBookingById, calculateRoomPrice,
 * isRoomAvailable, generateReport, getBasePrice (via public methods),
 * and sha256Hash (via createBooking).
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        // Inject the paymentApi field value (normally set via @Value)
        ReflectionTestUtils.setField(bookingService, "paymentApi",
                "https://payment-svc.internal/charge");
    }

    // =========================================================================
    // createBooking tests
    // =========================================================================

    @Test
    void createBooking_withValidParams_returnsMapWithBookingId() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("bookingId"));
        assertTrue(result.get("bookingId").toString().startsWith("BK-"));
    }

    @Test
    void createBooking_withValidParams_returnsGuestName() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Jane Doe", "DELUXE", "2024-04-01", "2024-04-03");

        // Assert
        assertEquals("Jane Doe", result.get("guestName"));
    }

    @Test
    void createBooking_withValidParams_returnsRoomType() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "VILLA", "2024-05-01", "2024-05-10");

        // Assert
        assertEquals("VILLA", result.get("roomType"));
    }

    @Test
    void createBooking_withValidParams_returnsCheckInAndCheckOut() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "STANDARD", "2024-06-01", "2024-06-07");

        // Assert
        assertEquals("2024-06-01", result.get("checkIn"));
        assertEquals("2024-06-07", result.get("checkOut"));
    }

    @Test
    void createBooking_withValidParams_returnsConfirmationCode() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "SUITE", "2024-07-01", "2024-07-05");

        // Assert
        assertNotNull(result.get("confirmationCode"));
        assertFalse(result.get("confirmationCode").toString().isEmpty());
    }

    @Test
    void createBooking_confirmationCode_isSha256HexString() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Dave", "DELUXE", "2024-08-01", "2024-08-04");

        // Assert — SHA-256 hex is 64 chars, all lowercase hex digits
        String code = result.get("confirmationCode").toString();
        assertEquals(64, code.length());
        assertTrue(code.matches("[0-9a-f]+"));
    }

    @Test
    void createBooking_callsJdbcTemplateUpdate_withParameterisedQuery() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        bookingService.createBooking("Eve", "STANDARD", "2024-09-01", "2024-09-03");

        // Assert — verify parameterised SQL was used (not string concatenation)
        verify(jdbcTemplate, times(1))
                .update(contains("?"), any(), any(), any(), any(), any());
    }

    @Test
    void createBooking_doesNotLeakDbHostInResponse() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Frank", "SUITE", "2024-10-01", "2024-10-05");

        // Assert — infrastructure details must not be exposed
        assertFalse(result.containsKey("dbHost"));
    }

    // =========================================================================
    // getBookingById tests
    // =========================================================================

    @Test
    void getBookingById_withExistingId_returnsBookingMap() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-12345678");
        dbRow.put("guest", "Grace");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-12345678")))
                .thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-12345678");

        // Assert
        assertNotNull(result);
        assertEquals("BK-12345678", result.get("id"));
        assertEquals("Grace", result.get("guest"));
    }

    @Test
    void getBookingById_withNonExistingId_returnsErrorEntry() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-NOTFOUND")))
                .thenThrow(new RuntimeException("No results"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTFOUND");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("BK-NOTFOUND"));
    }

    @Test
    void getBookingById_usesParameterisedQuery() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-ABCDEF12")))
                .thenReturn(new HashMap<>());

        // Act
        bookingService.getBookingById("BK-ABCDEF12");

        // Assert
        verify(jdbcTemplate, times(1))
                .queryForMap(contains("?"), eq("BK-ABCDEF12"));
    }

    // =========================================================================
    // calculateRoomPrice tests
    // =========================================================================

    @Test
    void calculateRoomPrice_standardRoomPeakSeasonNoLoyalty_returnsCorrectPrice() {
        // Arrange: STANDARD=120, PEAK*1.5=180, 3 nights => 540.00
        // Act
        String result = bookingService.calculateRoomPrice("STANDARD", 3, "PEAK", "NONE");
        // Assert
        assertEquals("540.00", result);
    }

    @Test
    void calculateRoomPrice_deluxeRoomOffSeasonGoldLoyalty_returnsCorrectPrice() {
        // Arrange: DELUXE=200, OFF*0.8=160, GOLD*0.9=144, 5 nights => 720.00
        String result = bookingService.calculateRoomPrice("DELUXE", 5, "OFF", "GOLD");
        assertEquals("720.00", result);
    }

    @Test
    void calculateRoomPrice_suiteRoomStandardSeasonPlatinumLoyalty_returnsCorrectPrice() {
        // Arrange: SUITE=350, no season multiplier, PLATINUM*0.8=280, 2 nights => 560.00
        String result = bookingService.calculateRoomPrice("SUITE", 2, "STANDARD", "PLATINUM");
        assertEquals("560.00", result);
    }

    @Test
    void calculateRoomPrice_villaRoomPeakSeasonDiamondLoyalty_returnsCorrectPrice() {
        // Arrange: VILLA=600, PEAK*1.5=900, DIAMOND*0.7=630, 1 night => 630.00
        String result = bookingService.calculateRoomPrice("VILLA", 1, "PEAK", "DIAMOND");
        assertEquals("630.00", result);
    }

    @Test
    void calculateRoomPrice_sevenNightsAppliesLongStayDiscount() {
        // Arrange: STANDARD=120, no season, no loyalty, 7 nights * 0.95 = 798.00
        String result = bookingService.calculateRoomPrice("STANDARD", 7, "STANDARD", "NONE");
        assertEquals("798.00", result);
    }

    @Test
    void calculateRoomPrice_fourteenNightsAppliesLargerLongStayDiscount() {
        // Arrange: STANDARD=120, no season, no loyalty, 14 nights * 0.90 = 1512.00
        String result = bookingService.calculateRoomPrice("STANDARD", 14, "STANDARD", "NONE");
        assertEquals("1512.00", result);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_returnsZero() {
        // Arrange: unknown room type => basePrice=0.0
        String result = bookingService.calculateRoomPrice("PENTHOUSE", 3, "PEAK", "GOLD");
        assertEquals("0.00", result);
    }

    @Test
    void calculateRoomPrice_offSeasonMultiplierApplied() {
        // Arrange: DELUXE=200, OFF*0.8=160, no loyalty, 1 night => 160.00
        String result = bookingService.calculateRoomPrice("DELUXE", 1, "OFF", "NONE");
        assertEquals("160.00", result);
    }

    @Test
    void calculateRoomPrice_peakSeasonMultiplierApplied() {
        // Arrange: DELUXE=200, PEAK*1.5=300, no loyalty, 1 night => 300.00
        String result = bookingService.calculateRoomPrice("DELUXE", 1, "PEAK", "NONE");
        assertEquals("300.00", result);
    }

    @ParameterizedTest
    @CsvSource({
        "STANDARD, 120.0",
        "DELUXE,   200.0",
        "SUITE,    350.0",
        "VILLA,    600.0"
    })
    void calculateRoomPrice_allRoomTypes_correctBasePrice(String roomType, double expectedBase) {
        // 1 night, no season modifier, no loyalty => total == basePrice
        String result = bookingService.calculateRoomPrice(roomType, 1, "STANDARD", "NONE");
        assertEquals(String.format("%.2f", expectedBase), result);
    }

    // =========================================================================
    // isRoomAvailable tests
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "DELUXE", "SUITE", "VILLA"})
    void isRoomAvailable_validRoomTypes_returnsTrue(String roomType) {
        assertTrue(bookingService.isRoomAvailable(roomType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENTHOUSE", "CABIN", "", "unknown"})
    void isRoomAvailable_invalidRoomTypes_returnsFalse(String roomType) {
        assertFalse(bookingService.isRoomAvailable(roomType));
    }

    // =========================================================================
    // generateReport tests
    // =========================================================================

    @Test
    void generateReport_withMonth_returnsMessageContainingMonth() {
        String result = bookingService.generateReport("2024-03");
        assertNotNull(result);
        assertTrue(result.contains("2024-03"));
    }

    @Test
    void generateReport_withMonth_returnsMessageContainingPaymentApi() {
        String result = bookingService.generateReport("2024-06");
        assertTrue(result.contains("https://payment-svc.internal/charge"));
    }

    @Test
    void generateReport_withDifferentMonth_returnsCorrectMessage() {
        String result = bookingService.generateReport("2025-01");
        assertTrue(result.contains("2025-01"));
    }

    // =========================================================================
    // Edge-case / boundary tests
    // =========================================================================

    @Test
    void createBooking_bookingIdFormat_startsWithBKPrefix() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        Map<String, Object> result = bookingService.createBooking(
                "Henry", "STANDARD", "2024-11-01", "2024-11-02");

        String bookingId = result.get("bookingId").toString();
        assertTrue(bookingId.startsWith("BK-"));
        // BK- + 8 uppercase hex chars = 11 chars total
        assertEquals(11, bookingId.length());
    }

    @Test
    void calculateRoomPrice_zeroNights_returnsZero() {
        String result = bookingService.calculateRoomPrice("SUITE", 0, "PEAK", "GOLD");
        assertEquals("0.00", result);
    }

    @Test
    void calculateRoomPrice_thirteenNights_appliesSevenNightDiscount() {
        // 13 nights: >= 7 but < 14 => 0.95 multiplier
        // STANDARD=120, no season, no loyalty, 13 * 0.95 = 12.35 * 120 = 1482.00
        String result = bookingService.calculateRoomPrice("STANDARD", 13, "STANDARD", "NONE");
        assertEquals("1482.00", result);
    }

    @Test
    void getBookingById_withEmptyId_returnsErrorEntry() {
        when(jdbcTemplate.queryForMap(anyString(), eq("")))
                .thenThrow(new RuntimeException("Empty ID"));

        Map<String, Object> result = bookingService.getBookingById("");
        assertTrue(result.containsKey("error"));
    }
}
