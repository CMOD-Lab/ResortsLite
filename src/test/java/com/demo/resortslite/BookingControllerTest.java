package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BookingController.
 */
@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
    }

    // -----------------------------------------------------------------------
    // createBooking
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Alice");
        mockBooking.put("roomType", "SUITE");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_withValidParams_responseContainsBooking() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03", session);

        // Assert
        assertTrue(response.containsKey("booking"));
        assertNotNull(response.get("booking"));
    }

    @Test
    void createBooking_storesLastBookingInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Carol", "VILLA", "2024-08-01", "2024-08-07", session);

        // Assert
        assertNotNull(session.getAttribute("lastBooking"));
    }

    @Test
    void createBooking_storesGuestNameInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Dave", "STANDARD", "2024-09-01", "2024-09-03", session);

        // Assert
        assertEquals("Dave", session.getAttribute("guestName"));
    }

    @Test
    void createBooking_callsBookingServiceOnce() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-05", session);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Eve", "SUITE", "2024-10-01", "2024-10-05");
    }

    @Test
    void createBooking_bookingAddedToCache() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-CACHE001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Frank", "DELUXE", "2024-11-01", "2024-11-04", session);

        // Assert — booking is returned in response
        assertNotNull(response.get("booking"));
    }

    // -----------------------------------------------------------------------
    // getBookingStatus
    // -----------------------------------------------------------------------

    @Test
    void getBookingStatus_withValidBookingId_returnsResultMap() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-12345678");
        mockDetails.put("guest", "Alice");
        when(bookingService.getBookingById("BK-12345678")).thenReturn(mockDetails);
        session.setAttribute("guestName", "Alice");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-12345678", session);

        // Assert
        assertNotNull(result);
        assertEquals("BK-12345678", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_returnsSessionGuestName() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());
        session.setAttribute("guestName", "Bob");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ANY", session);

        // Assert
        assertEquals("Bob", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_withNoSessionGuest_sessionGuestIsNull() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());
        // No guestName set in session

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ANY", session);

        // Assert
        assertNull(result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_containsDetailsKey() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-XYZ");
        when(bookingService.getBookingById("BK-XYZ")).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-XYZ", session);

        // Assert
        assertTrue(result.containsKey("details"));
    }

    @Test
    void getBookingStatus_callsGetBookingByIdOnce() {
        // Arrange
        when(bookingService.getBookingById("BK-TEST")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-TEST", session);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-TEST");
    }

    // -----------------------------------------------------------------------
    // checkAvailability
    // -----------------------------------------------------------------------

    @Test
    void checkAvailability_withAvailableRoom_returnsAvailableTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(response);
        assertEquals(true, response.get("available"));
    }

    @Test
    void checkAvailability_withUnavailableRoom_returnsAvailableFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, response.get("available"));
    }

    @Test
    void checkAvailability_responseContainsRoomType() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals("DELUXE", response.get("roomType"));
    }

    @Test
    void checkAvailability_responseContainsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        // Assert
        assertTrue(response.containsKey("inventoryEndpoint"));
        assertNotNull(response.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_callsIsRoomAvailableOnce() {
        // Arrange
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        // Act
        bookingController.checkAvailability("VILLA");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("VILLA");
    }

    // -----------------------------------------------------------------------
    // downloadReport
    // -----------------------------------------------------------------------

    @Test
    void downloadReport_withValidMonth_returnsResponseMap() {
        // Arrange
        when(bookingService.generateReport("2024-03")).thenReturn("Report triggered for 2024-03");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-03");

        // Assert
        assertNotNull(response);
    }

    @Test
    void downloadReport_responseContainsReportPath() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("Report triggered");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-06");

        // Assert
        assertTrue(response.containsKey("reportPath"));
    }

    @Test
    void downloadReport_reportPathContainsMonth() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("Report triggered");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-06");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.contains("2024-06"));
    }

    @Test
    void downloadReport_reportPathEndsWithPdf() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("Report triggered");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-06");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.endsWith(".pdf"));
    }

    @Test
    void downloadReport_responseContainsMessage() {
        // Arrange
        when(bookingService.generateReport("2024-12")).thenReturn("Report triggered for 2024-12");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-12");

        // Assert
        assertTrue(response.containsKey("message"));
        assertEquals("Report triggered for 2024-12", response.get("message"));
    }

    @Test
    void downloadReport_callsGenerateReportOnce() {
        // Arrange
        when(bookingService.generateReport("2024-01")).thenReturn("done");

        // Act
        bookingController.downloadReport("2024-01");

        // Assert
        verify(bookingService, times(1)).generateReport("2024-01");
    }
}
