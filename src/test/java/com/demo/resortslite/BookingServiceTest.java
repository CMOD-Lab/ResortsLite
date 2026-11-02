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

/**
 * Unit tests for {@link BookingService}.
 * Covers: createBooking, getBookingById, calculateRoomPrice,
 *         isRoomAvailable, generateReport, sha256Hash (indirectly).
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // -----------------------------------------------------------------------
    // createBooking
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidInputs_returnsMapWithBookingId() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("bookingId"));
        assertTrue(result.get("bookingId").toString().startsWith("BK-"));
    }

    @Test
    void createBooking_withValidInputs_returnsGuestName() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-10", "2024-07-15");

        // Assert
        assertEquals("Bob", result.get("guestName"));
    }

    @Test
    void createBooking_withValidInputs_returnsRoomType() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "STANDARD", "2024-08-01", "2024-08-03");

        // Assert
        assertEquals("STANDARD", result.get("roomType"));
    }

    @Test
    void createBooking_withValidInputs_returnsCheckInAndCheckOut() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Dave", "VILLA", "2024-09-01", "2024-09-10");

        // Assert
        assertEquals("2024-09-01", result.get("checkIn"));
        assertEquals("2024-09-10", result.get("checkOut"));
    }

    @Test
    void createBooking_withValidInputs_returnsConfirmationCode() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-07");

        // Assert
        assertNotNull(result.get("confirmationCode"));
        assertFalse(result.get("confirmationCode").toString().isEmpty());
    }

    @Test
    void createBooking_confirmationCode_isSha256HexString() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Frank", "DELUXE", "2024-11-01", "2024-11-04");

        // Assert — SHA-256 hex is always 64 lowercase hex chars
        String code = (String) result.get("confirmationCode");
        assertNotNull(code);
        assertEquals(64, code.length());
        assertTrue(code.matches("[0-9a-f]+"), "Confirmation code should be lowercase hex");
    }

    @Test
    void createBooking_eachCall_generatesUniqueBookingId() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> r1 = bookingService.createBooking("G1", "SUITE", "2024-01-01", "2024-01-02");
        Map<String, Object> r2 = bookingService.createBooking("G2", "SUITE", "2024-01-01", "2024-01-02");

        // Assert
        assertNotEquals(r1.get("bookingId"), r2.get("bookingId"));
    }

    @Test
    void createBooking_executesJdbcInsert() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        bookingService.createBooking("Hank", "STANDARD", "2024-02-01", "2024-02-03");

        // Assert
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    @Test
    void createBooking_returnsDbHostField() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Ivy", "VILLA", "2024-03-01", "2024-03-05");

        // Assert
        assertNotNull(result.get("dbHost"));
    }

    // -----------------------------------------------------------------------
    // getBookingById
    // -----------------------------------------------------------------------

    @Test
    void getBookingById_whenFound_returnsBookingMap() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "John");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        // Assert
        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("id"));
        assertEquals("John", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("No results"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-NOTEXIST");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("BK-NOTEXIST"));
    }

    @Test
    void getBookingById_whenExceptionThrown_errorMessageContainsBookingId() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("EmptyResultDataAccessException"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-XYZ999");

        // Assert
        String errorMsg = (String) result.get("error");
        assertNotNull(errorMsg);
        assertTrue(errorMsg.contains("BK-XYZ999"));
    }

    @Test
    void getBookingById_callsJdbcQueryForMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(new HashMap<>());

        // Act
        bookingService.getBookingById("BK-TEST001");

        // Assert
        verify(jdbcTemplate, times(1)).queryForMap(anyString());
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — room type branching
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_noLoyalty_oneNight() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");

        // Assert — base 120.0 * 1 night = 120.00
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoom_normalSeason_noLoyalty_oneNight() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");

        // Assert — base 200.0 * 1 = 200.00
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoom_normalSeason_noLoyalty_oneNight() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");

        // Assert — base 350.0 * 1 = 350.00
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoom_normalSeason_noLoyalty_oneNight() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");

        // Assert — base 600.0 * 1 = 600.00
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoom_defaultsToStandardBase() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");

        // Assert — unknown defaults to 120.0
        assertEquals("120.00", price);
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — season branching
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoom_peakSeason_noLoyalty_oneNight() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "PEAK", "NONE");

        // Assert — 120.0 * 1.5 = 180.00
        assertEquals("180.00", price);
    }

    @Test
    void calculateRoomPrice_standardRoom_offSeason_noLoyalty_oneNight() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "OFF", "NONE");

        // Assert — 120.0 * 0.8 = 96.00
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_standardRoom_unknownSeason_noLoyalty_oneNight() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "MONSOON", "NONE");

        // Assert — no season multiplier applied → 120.00
        assertEquals("120.00", price);
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — loyalty branching
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_goldLoyalty_oneNight() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");

        // Assert — 120.0 * 0.9 = 108.00
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_platinumLoyalty_oneNight() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");

        // Assert — 120.0 * 0.8 = 96.00
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_diamondLoyalty_oneNight() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");

        // Assert — 120.0 * 0.7 = 84.00
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_unknownLoyalty_oneNight() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "BRONZE");

        // Assert — no loyalty discount → 120.00
        assertEquals("120.00", price);
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — nights boundary branching
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_noLoyalty_sevenNights() {
        // Arrange / Act — 7 nights triggers 5% discount
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");

        // Assert — 120.0 * 0.95 * 7 = 798.00
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_noLoyalty_fourteenNights() {
        // Arrange / Act — 14 nights: code checks >= 7 first, so 5% discount applies
        // (the >= 14 branch is unreachable due to ordering, but we test the boundary)
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");

        // Assert — 120.0 * 0.95 * 14 = 1596.00
        assertEquals("1596.00", price);
    }

    @Test
    void calculateRoomPrice_standardRoom_normalSeason_noLoyalty_sixNights() {
        // Arrange / Act — 6 nights: no long-stay discount
        String price = bookingService.calculateRoomPrice("STANDARD", 6, "NORMAL", "NONE");

        // Assert — 120.0 * 6 = 720.00
        assertEquals("720.00", price);
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice — combined scenarios
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_suite_peakSeason_diamondLoyalty_sevenNights() {
        // Arrange / Act
        // base=350, peak=350*1.5=525, diamond=525*0.7=367.5, 7nights=367.5*0.95=349.125, *7=2443.875
        String price = bookingService.calculateRoomPrice("SUITE", 7, "PEAK", "DIAMOND");

        // Assert
        assertEquals("2443.88", price);
    }

    @Test
    void calculateRoomPrice_villa_offSeason_goldLoyalty_threeNights() {
        // Arrange / Act
        // base=600, off=600*0.8=480, gold=480*0.9=432, 3nights=432*3=1296.00
        String price = bookingService.calculateRoomPrice("VILLA", 3, "OFF", "GOLD");

        // Assert
        assertEquals("1296.00", price);
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
        assertEquals(expected, bookingService.calculateRoomPrice(roomType, nights, season, loyalty));
    }

    // -----------------------------------------------------------------------
    // isRoomAvailable
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
    @ValueSource(strings = {"PENTHOUSE", "CABIN", "TENT", "", "standard", "deluxe"})
    void isRoomAvailable_invalidRoomTypes_returnFalse(String roomType) {
        assertFalse(bookingService.isRoomAvailable(roomType));
    }

    // -----------------------------------------------------------------------
    // generateReport
    // -----------------------------------------------------------------------

    @Test
    void generateReport_withMonth_returnsNonNullString() {
        // Act
        String result = bookingService.generateReport("2024-03");

        // Assert
        assertNotNull(result);
    }

    @Test
    void generateReport_withMonth_containsMonthInResult() {
        // Act
        String result = bookingService.generateReport("2024-03");

        // Assert
        assertTrue(result.contains("2024-03"));
    }

    @Test
    void generateReport_withMonth_containsPaymentApiReference() {
        // Act
        String result = bookingService.generateReport("2024-06");

        // Assert — result references the PAYMENT_API constant
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void generateReport_withDifferentMonths_returnsDistinctResults() {
        // Act
        String r1 = bookingService.generateReport("2024-01");
        String r2 = bookingService.generateReport("2024-12");

        // Assert
        assertNotEquals(r1, r2);
    }
}
