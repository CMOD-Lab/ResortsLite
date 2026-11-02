package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BookingController}.
 * Covers: createBooking, getBookingStatus, checkAvailability, downloadReport.
 */
@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @Mock
    private HttpSession httpSession;

    @InjectMocks
    private BookingController bookingController;

    private Map<String, Object> sampleBooking;

    @BeforeEach
    void setUp() {
        sampleBooking = new HashMap<>();
        sampleBooking.put("bookingId", "BK-ABCD1234");
        sampleBooking.put("guestName", "Alice");
        sampleBooking.put("roomType", "SUITE");
        sampleBooking.put("checkIn", "2024-06-01");
        sampleBooking.put("checkOut", "2024-06-05");
        sampleBooking.put("confirmationCode", "abc123def456");
    }

    // -----------------------------------------------------------------------
    // createBooking
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);
        doNothing().when(httpSession).setAttribute(anyString(), any());

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", httpSession);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_withValidParams_returnsBookingInResponse() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);
        doNothing().when(httpSession).setAttribute(anyString(), any());

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", httpSession);

        // Assert
        assertNotNull(response.get("booking"));
        assertEquals(sampleBooking, response.get("booking"));
    }

    @Test
    void createBooking_setsLastBookingInSession() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);
        doNothing().when(httpSession).setAttribute(anyString(), any());

        // Act
        bookingController.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05", httpSession);

        // Assert
        verify(httpSession).setAttribute(eq("lastBooking"), eq(sampleBooking));
    }

    @Test
    void createBooking_setsGuestNameInSession() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);
        doNothing().when(httpSession).setAttribute(anyString(), any());

        // Act
        bookingController.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05", httpSession);

        // Assert
        verify(httpSession).setAttribute(eq("guestName"), eq("Alice"));
    }

    @Test
    void createBooking_callsBookingServiceCreateBooking() {
        // Arrange
        when(bookingService.createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-05"))
                .thenReturn(sampleBooking);
        doNothing().when(httpSession).setAttribute(anyString(), any());

        // Act
        bookingController.createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-05", httpSession);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-05");
    }

    @Test
    void createBooking_responseContainsTwoKeys() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);
        doNothing().when(httpSession).setAttribute(anyString(), any());

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Carol", "VILLA", "2024-08-01", "2024-08-10", httpSession);

        // Assert — response has "status" and "booking"
        assertTrue(response.containsKey("status"));
        assertTrue(response.containsKey("booking"));
    }

    // -----------------------------------------------------------------------
    // getBookingStatus
    // -----------------------------------------------------------------------

    @Test
    void getBookingStatus_withValidBookingId_returnsBookingIdInResponse() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn("Alice");
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(sampleBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", httpSession);

        // Assert
        assertEquals("BK-ABCD1234", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_withSessionGuest_returnsSessionGuestInResponse() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn("Alice");
        when(bookingService.getBookingById(anyString())).thenReturn(sampleBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", httpSession);

        // Assert
        assertEquals("Alice", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_withNullSessionGuest_returnsNullSessionGuest() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn(null);
        when(bookingService.getBookingById(anyString())).thenReturn(sampleBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", httpSession);

        // Assert
        assertNull(result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_returnsDetailsFromService() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn("Bob");
        when(bookingService.getBookingById("BK-XYZ999")).thenReturn(sampleBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-XYZ999", httpSession);

        // Assert
        assertEquals(sampleBooking, result.get("details"));
    }

    @Test
    void getBookingStatus_callsGetBookingByIdOnService() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn("Carol");
        when(bookingService.getBookingById("BK-TEST")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-TEST", httpSession);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-TEST");
    }

    @Test
    void getBookingStatus_responseContainsThreeKeys() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn("Dave");
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-001", httpSession);

        // Assert
        assertTrue(result.containsKey("bookingId"));
        assertTrue(result.containsKey("sessionGuest"));
        assertTrue(result.containsKey("details"));
    }

    // -----------------------------------------------------------------------
    // checkAvailability
    // -----------------------------------------------------------------------

    @Test
    void checkAvailability_withValidRoomType_returnsRoomTypeInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertEquals("SUITE", result.get("roomType"));
    }

    @Test
    void checkAvailability_whenRoomAvailable_returnsTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals(true, result.get("available"));
    }

    @Test
    void checkAvailability_whenRoomNotAvailable_returnsFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, result.get("available"));
    }

    @Test
    void checkAvailability_responseContainsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        // Assert
        assertNotNull(result.get("inventoryEndpoint"));
        assertTrue(result.get("inventoryEndpoint").toString().contains("inventory-service"));
    }

    @Test
    void checkAvailability_callsIsRoomAvailableOnService() {
        // Arrange
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        // Act
        bookingController.checkAvailability("VILLA");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("VILLA");
    }

    @Test
    void checkAvailability_responseContainsThreeKeys() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("UNKNOWN");

        // Assert
        assertTrue(result.containsKey("roomType"));
        assertTrue(result.containsKey("inventoryEndpoint"));
        assertTrue(result.containsKey("available"));
    }

    // -----------------------------------------------------------------------
    // downloadReport
    // -----------------------------------------------------------------------

    @Test
    void downloadReport_withMonth_returnsReportPathInResponse() {
        // Arrange
        when(bookingService.generateReport("2024-03")).thenReturn("Report generated for 2024-03");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-03");

        // Assert
        assertNotNull(result.get("reportPath"));
        assertTrue(result.get("reportPath").toString().contains("2024-03"));
    }

    @Test
    void downloadReport_withMonth_returnsMessageFromService() {
        // Arrange
        String expectedMsg = "Report generation triggered for: 2024-06";
        when(bookingService.generateReport("2024-06")).thenReturn(expectedMsg);

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-06");

        // Assert
        assertEquals(expectedMsg, result.get("message"));
    }

    @Test
    void downloadReport_reportPathContainsLegacyPath() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("ok");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-01");

        // Assert
        String path = (String) result.get("reportPath");
        assertTrue(path.contains("/var/legacy/reports/"));
    }

    @Test
    void downloadReport_reportPathContainsPdfExtension() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("ok");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-12");

        // Assert
        String path = (String) result.get("reportPath");
        assertTrue(path.endsWith(".pdf"));
    }

    @Test
    void downloadReport_callsGenerateReportOnService() {
        // Arrange
        when(bookingService.generateReport("2024-09")).thenReturn("done");

        // Act
        bookingController.downloadReport("2024-09");

        // Assert
        verify(bookingService, times(1)).generateReport("2024-09");
    }

    @Test
    void downloadReport_responseContainsTwoKeys() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("done");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-05");

        // Assert
        assertTrue(result.containsKey("reportPath"));
        assertTrue(result.containsKey("message"));
    }
}
