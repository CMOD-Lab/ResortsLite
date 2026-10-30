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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Alice");
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
    void createBooking_responseContainsBookingObject() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-TEST0001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03", session);

        // Assert
        assertTrue(response.containsKey("booking"), "Response must contain 'booking' key");
        assertNotNull(response.get("booking"));
    }

    @Test
    void createBooking_storesLastBookingInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-SESSION01");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Carol", "STANDARD", "2024-08-01", "2024-08-03", session);

        // Assert
        assertNotNull(session.getAttribute("lastBooking"), "Session should store lastBooking");
    }

    @Test
    void createBooking_storesGuestNameInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-SESSION02");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Dave", "VILLA", "2024-09-01", "2024-09-05", session);

        // Assert
        assertEquals("Dave", session.getAttribute("guestName"),
                "Session should store the guest name");
    }

    @Test
    void createBooking_callsBookingServiceOnce() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-VERIFY01");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-04", session);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Eve", "SUITE", "2024-10-01", "2024-10-04");
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
                "Frank", "DELUXE", "2024-11-01", "2024-11-02", session);

        // Assert – booking object is returned in response
        assertNotNull(response.get("booking"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingStatus tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingStatus_withValidBookingId_returnsResultMap() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-STATUS01");
        when(bookingService.getBookingById("BK-STATUS01")).thenReturn(mockDetails);
        session.setAttribute("guestName", "Grace");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS01", session);

        // Assert
        assertNotNull(result);
        assertEquals("BK-STATUS01", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_includesSessionGuestName() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());
        session.setAttribute("guestName", "Henry");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-001", session);

        // Assert
        assertEquals("Henry", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_withNoSessionGuest_sessionGuestIsNull() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());
        // No guestName set in session

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-002", session);

        // Assert
        assertNull(result.get("sessionGuest"),
                "sessionGuest should be null when not set in session");
    }

    @Test
    void getBookingStatus_includesDetailsFromService() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("guest", "Iris");
        mockDetails.put("room", "SUITE");
        when(bookingService.getBookingById("BK-DETAIL01")).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-DETAIL01", session);

        // Assert
        assertNotNull(result.get("details"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.get("details");
        assertEquals("Iris", details.get("guest"));
    }

    @Test
    void getBookingStatus_callsBookingServiceWithCorrectId() {
        // Arrange
        when(bookingService.getBookingById("BK-VERIFY02")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-VERIFY02", session);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-VERIFY02");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // checkAvailability tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void checkAvailability_withValidRoomType_returnsResponseMap() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(response);
        assertEquals("SUITE", response.get("roomType"));
    }

    @Test
    void checkAvailability_whenRoomAvailable_returnsTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        // Assert
        assertTrue((Boolean) response.get("available"));
    }

    @Test
    void checkAvailability_whenRoomNotAvailable_returnsFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertFalse((Boolean) response.get("available"));
    }

    @Test
    void checkAvailability_responseContainsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        // Assert
        assertNotNull(response.get("inventoryEndpoint"),
                "Response should contain inventoryEndpoint");
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

    // ─────────────────────────────────────────────────────────────────────────
    // downloadReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void downloadReport_withValidMonth_returnsResponseMap() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report for March");

        // Act
        Map<String, Object> response = bookingController.downloadReport("March");

        // Assert
        assertNotNull(response);
    }

    @Test
    void downloadReport_responseContainsReportPath() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("Report generated");

        // Act
        Map<String, Object> response = bookingController.downloadReport("June");

        // Assert
        assertNotNull(response.get("reportPath"), "Response should contain reportPath");
    }

    @Test
    void downloadReport_reportPathContainsMonth() {
        // Arrange
        when(bookingService.generateReport("July")).thenReturn("Report for July");

        // Act
        Map<String, Object> response = bookingController.downloadReport("July");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.contains("July"), "Report path should contain the month");
    }

    @Test
    void downloadReport_responseContainsMessage() {
        // Arrange
        when(bookingService.generateReport("August")).thenReturn("Report for August");

        // Act
        Map<String, Object> response = bookingController.downloadReport("August");

        // Assert
        assertEquals("Report for August", response.get("message"));
    }

    @Test
    void downloadReport_callsGenerateReportOnce() {
        // Arrange
        when(bookingService.generateReport("September")).thenReturn("Report for September");

        // Act
        bookingController.downloadReport("September");

        // Assert
        verify(bookingService, times(1)).generateReport("September");
    }
}
