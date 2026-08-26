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
 * Unit tests for {@link BookingController}.
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

    // -------------------------------------------------------------------------
    // createBooking tests
    // -------------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Alice");
        mockBooking.put("roomType", "SUITE");
        mockBooking.put("checkIn", "2024-06-01");
        mockBooking.put("checkOut", "2024-06-05");
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
    void createBooking_withValidParams_responseContainsBookingKey() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03", session);

        // Assert
        assertTrue(response.containsKey("booking"), "Response should contain 'booking' key");
    }

    @Test
    void createBooking_storesBookingInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Charlie", "STANDARD", "2024-08-01", "2024-08-04", session);

        // Assert
        assertNotNull(session.getAttribute("lastBooking"), "Session should store lastBooking");
    }

    @Test
    void createBooking_storesGuestNameInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Diana", "VILLA", "2024-09-01", "2024-09-10", session);

        // Assert
        assertEquals("Diana", session.getAttribute("guestName"),
                "Session should store the guest name");
    }

    @Test
    void createBooking_callsBookingServiceCreateBooking() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-05"))
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
                "Frank", "DELUXE", "2024-11-01", "2024-11-05", session);

        // Assert — booking object in response should match the mock
        @SuppressWarnings("unchecked")
        Map<String, Object> bookingInResponse = (Map<String, Object>) response.get("booking");
        assertNotNull(bookingInResponse);
        assertEquals("BK-CACHE001", bookingInResponse.get("bookingId"));
    }

    // -------------------------------------------------------------------------
    // getBookingStatus tests
    // -------------------------------------------------------------------------

    @Test
    void getBookingStatus_returnsMapWithBookingId() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-STATUS01");
        when(bookingService.getBookingById("BK-STATUS01")).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS01", session);

        // Assert
        assertNotNull(result);
        assertEquals("BK-STATUS01", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_returnsSessionGuestName() {
        // Arrange
        session.setAttribute("guestName", "Grace");
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-001", session);

        // Assert
        assertEquals("Grace", result.get("sessionGuest"),
                "Response should contain the session guest name");
    }

    @Test
    void getBookingStatus_whenNoSessionGuest_sessionGuestIsNull() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-002", session);

        // Assert
        assertNull(result.get("sessionGuest"),
                "sessionGuest should be null when not set in session");
    }

    @Test
    void getBookingStatus_containsDetailsKey() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("guest", "Henry");
        when(bookingService.getBookingById("BK-003")).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-003", session);

        // Assert
        assertTrue(result.containsKey("details"), "Response should contain 'details' key");
    }

    @Test
    void getBookingStatus_callsBookingServiceGetBookingById() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById("BK-004")).thenReturn(mockDetails);

        // Act
        bookingController.getBookingStatus("BK-004", session);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-004");
    }

    // -------------------------------------------------------------------------
    // checkAvailability tests
    // -------------------------------------------------------------------------

    @Test
    void checkAvailability_withValidRoomType_returnsMapWithRoomType() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(result);
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
    void checkAvailability_containsInventoryEndpointKey() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        // Assert
        assertTrue(result.containsKey("inventoryEndpoint"),
                "Response should contain 'inventoryEndpoint' key");
    }

    @Test
    void checkAvailability_inventoryEndpointUsesHttps() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("VILLA");
        String endpoint = (String) result.get("inventoryEndpoint");

        // Assert
        assertNotNull(endpoint);
        assertTrue(endpoint.startsWith("https://"),
                "Inventory endpoint should use HTTPS");
    }

    @Test
    void checkAvailability_callsBookingServiceIsRoomAvailable() {
        // Arrange
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        // Act
        bookingController.checkAvailability("STANDARD");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("STANDARD");
    }

    // -------------------------------------------------------------------------
    // downloadReport tests
    // -------------------------------------------------------------------------

    @Test
    void downloadReport_returnsMapWithReportPath() {
        // Arrange
        when(bookingService.generateReport("2024-03")).thenReturn("Report triggered for 2024-03");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-03");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("reportPath"), "Response should contain 'reportPath'");
    }

    @Test
    void downloadReport_reportPathContainsMonth() {
        // Arrange
        when(bookingService.generateReport("2024-06")).thenReturn("Report triggered for 2024-06");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-06");
        String reportPath = (String) result.get("reportPath");

        // Assert
        assertNotNull(reportPath);
        assertTrue(reportPath.contains("2024-06"),
                "Report path should contain the month");
    }

    @Test
    void downloadReport_reportPathEndsWithPdfExtension() {
        // Arrange
        when(bookingService.generateReport("2024-09")).thenReturn("Report triggered");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-09");
        String reportPath = (String) result.get("reportPath");

        // Assert
        assertNotNull(reportPath);
        assertTrue(reportPath.endsWith("_bookings.pdf"),
                "Report path should end with '_bookings.pdf'");
    }

    @Test
    void downloadReport_containsMessageKey() {
        // Arrange
        when(bookingService.generateReport("2024-12")).thenReturn("Report triggered for 2024-12");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-12");

        // Assert
        assertTrue(result.containsKey("message"), "Response should contain 'message' key");
    }

    @Test
    void downloadReport_messageMatchesServiceResponse() {
        // Arrange
        String expectedMessage = "Report generation triggered for: 2024-01 via https://...";
        when(bookingService.generateReport("2024-01")).thenReturn(expectedMessage);

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-01");

        // Assert
        assertEquals(expectedMessage, result.get("message"));
    }

    @Test
    void downloadReport_callsBookingServiceGenerateReport() {
        // Arrange
        when(bookingService.generateReport("2024-05")).thenReturn("triggered");

        // Act
        bookingController.downloadReport("2024-05");

        // Assert
        verify(bookingService, times(1)).generateReport("2024-05");
    }
}
