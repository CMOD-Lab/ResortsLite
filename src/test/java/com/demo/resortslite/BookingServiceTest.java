package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * Unit tests for {@link BookingService}.
 * JdbcTemplate is mocked so no real database is required.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        // Inject the @Value field that Spring would normally populate
        ReflectionTestUtils.setField(bookingService, "paymentApi",
                "https://payment-svc.internal/charge");
    }

    // -----------------------------------------------------------------------
    // createBooking()
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidInputs_returnsBookingMap() {
        // Arrange
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result, "Result must not be null");
        assertEquals("Alice", result.get("guestName"));
        assertEquals("SUITE", result.get("roomType"));
        assertEquals("2024-06-01", result.get("checkIn"));
        assertEquals("2024-06-05", result.get("checkOut"));
    }

    @Test
    void createBooking_generatesBookingIdWithBkPrefix() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        String bookingId = (String) result.get("bookingId");
        assertNotNull(bookingId, "bookingId must not be null");
        assertTrue(bookingId.startsWith("BK-"),
                "bookingId must start with 'BK-'");
    }

    @Test
    void createBooking_generatesNonNullConfirmationCode() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        Map<String, Object> result = bookingService.createBooking(
                "Carol", "STANDARD", "2024-08-10", "2024-08-12");

        assertNotNull(result.get("confirmationCode"),
                "confirmationCode must not be null");
    }

    @Test
    void createBooking_confirmationCodeIsSha256Hex() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        Map<String, Object> result = bookingService.createBooking(
                "Dave", "VILLA", "2024-09-01", "2024-09-07");

        String code = (String) result.get("confirmationCode");
        // SHA-256 hex is always 64 lowercase hex characters
        assertNotNull(code);
        assertEquals(64, code.length(),
                "SHA-256 hex digest must be 64 characters long");
        assertTrue(code.matches("[0-9a-f]+"),
                "SHA-256 hex digest must contain only lowercase hex characters");
    }

    @Test
    void createBooking_invokesJdbcTemplateUpdate() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        bookingService.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-04");

        verify(jdbcTemplate, times(1))
                .update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void createBooking_doesNotExposeDbHostInResponse() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        Map<String, Object> result = bookingService.createBooking(
                "Frank", "DELUXE", "2024-11-01", "2024-11-03");

        assertFalse(result.containsKey("dbHost"),
                "Internal DB_HOST must not be exposed in the API response");
    }

    // -----------------------------------------------------------------------
    // getBookingById()
    // -----------------------------------------------------------------------

    @Test
    void getBookingById_withExistingId_returnsBookingData() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "Grace");
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-ABCD1234")))
                .thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        // Assert
        assertNotNull(result);
        assertEquals("Grace", result.get("guest"));
    }

    @Test
    void getBookingById_withNonExistentId_returnsErrorMap() {
        // Arrange — simulate "no rows" exception from JdbcTemplate
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-UNKNOWN")))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-UNKNOWN");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"),
                "Result must contain an 'error' key when booking is not found");
        assertTrue(result.get("error").toString().contains("BK-UNKNOWN"),
                "Error message must include the requested bookingId");
    }

    @Test
    void getBookingById_usesParameterisedQuery() {
        when(jdbcTemplate.queryForMap(anyString(), eq("BK-TEST")))
                .thenReturn(new HashMap<>());

        bookingService.getBookingById("BK-TEST");

        // Verify parameterised call (not string concatenation)
        verify(jdbcTemplate).queryForMap(contains("WHERE id = ?"), eq("BK-TEST"));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice()
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoomOneNightNormalSeason_returnsBaseRate() {
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomOneNightNormalSeason_returnsBaseRate() {
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomOneNightNormalSeason_returnsBaseRate() {
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomOneNightNormalSeason_returnsBaseRate() {
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_defaultsToStandardRate() {
        String price = bookingService.calculateRoomPrice("CABIN", 1, "NORMAL", "NONE");
        assertEquals("120.00", price,
                "Unknown room type should default to STANDARD base rate of 120.00");
    }

    @Test
    void calculateRoomPrice_peakSeasonMultiplier_appliedCorrectly() {
        // STANDARD 120 * 1.5 (PEAK) * 1 night = 180.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_offSeasonMultiplier_appliedCorrectly() {
        // STANDARD 120 * 0.8 (OFF) * 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_goldLoyaltyDiscount_appliedCorrectly() {
        // STANDARD 120 * 0.9 (GOLD) * 1 night = 108.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyaltyDiscount_appliedCorrectly() {
        // STANDARD 120 * 0.8 (PLATINUM) * 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyaltyDiscount_appliedCorrectly() {
        // STANDARD 120 * 0.7 (DIAMOND) * 1 night = 84.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNightsStayDiscount_appliedCorrectly() {
        // STANDARD 120 * 1.0 * 1.0 * 0.95 * 7 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNightsStayDiscount_appliedCorrectly() {
        // STANDARD 120 * 1.0 * 1.0 * 0.90 * 14 = 1512.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_combinedPeakAndDiamondAndLongStay() {
        // SUITE 350 * 1.5 (PEAK) * 0.7 (DIAMOND) * 0.90 (14 nights) * 14 = 4630.50
        String price = bookingService.calculateRoomPrice("SUITE", 14, "PEAK", "DIAMOND");
        assertEquals("4630.50", price);
    }

    @ParameterizedTest
    @CsvSource({
            "STANDARD, 1, NORMAL, NONE,   120.00",
            "DELUXE,   1, PEAK,   NONE,   300.00",
            "SUITE,    1, OFF,    GOLD,   252.00",
            "VILLA,    7, NORMAL, NONE,   3990.00",
            "VILLA,    14, PEAK,  DIAMOND, 7938.00"
    })
    void calculateRoomPrice_parameterisedScenarios(
            String roomType, int nights, String season, String loyalty, String expected) {
        assertEquals(expected,
                bookingService.calculateRoomPrice(roomType, nights, season, loyalty));
    }

    // -----------------------------------------------------------------------
    // isRoomAvailable()
    // -----------------------------------------------------------------------

    @Test
    void isRoomAvailable_withValidRoomType_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
        assertTrue(bookingService.isRoomAvailable("SUITE"));
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_withInvalidRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    void isRoomAvailable_withNull_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(null));
    }

    @Test
    void isRoomAvailable_withEmptyString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    // -----------------------------------------------------------------------
    // generateReport()
    // -----------------------------------------------------------------------

    @Test
    void generateReport_returnsStringContainingMonth() {
        String result = bookingService.generateReport("2024-03");
        assertNotNull(result);
        assertTrue(result.contains("2024-03"),
                "Report message must contain the requested month");
    }

    @Test
    void generateReport_returnsStringContainingPaymentApi() {
        String result = bookingService.generateReport("2024-04");
        assertNotNull(result);
        assertTrue(result.contains("https://payment-svc.internal/charge"),
                "Report message must reference the payment API endpoint");
    }

    @Test
    void generateReport_withDifferentMonths_returnsDistinctMessages() {
        String march = bookingService.generateReport("2024-03");
        String april = bookingService.generateReport("2024-04");
        assertNotEquals(march, april,
                "Reports for different months must produce different messages");
    }
}
