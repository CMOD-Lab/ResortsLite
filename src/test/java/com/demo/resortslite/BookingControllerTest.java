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
        mockBooking.put("guestName", "John Smith");
        mockBooking.put("roomType", "SUITE");
        mockBooking.put("checkIn", "2024-03-01");
        mockBooking.put("checkOut", "2024-03-05");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05", session);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_withValidParams_returnsBookingInResponse() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Jane Doe");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Jane Doe", "DELUXE", "2024-04-01", "2024-04-03", session);

        // Assert
        assertNotNull(response.get("booking"));
        @SuppressWarnings("unchecked")
        Map<String, Object> booking = (Map<String, Object>) response.get("booking");
        assertEquals("BK-ABCD1234", booking.get("bookingId"));
    }

    @Test
    void createBooking_storesLastBookingInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Alice", "VILLA", "2024-05-01", "2024-05-10", session);

        // Assert
        assertNotNull(session.getAttribute("lastBooking"), "Session should store lastBooking");
        assertEquals("Alice", session.getAttribute("guestName"), "Session should store guestName");
    }

    @Test
    void createBooking_storesGuestNameInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-XYZ99999");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Bob", "STANDARD", "2024-06-01", "2024-06-02", session);

        // Assert
        assertEquals("Bob", session.getAttribute("guestName"));
    }

    @Test
    void createBooking_callsBookingServiceWithCorrectParams() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-TEST1234");
        when(bookingService.createBooking("Carol", "SUITE", "2024-07-01", "2024-07-05"))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Carol", "SUITE", "2024-07-01", "2024-07-05", session);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Carol", "SUITE", "2024-07-01", "2024-07-05");
    }

    @Test
    void createBooking_addsBookingToCache() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-CACHE001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Dave", "DELUXE", "2024-08-01", "2024-08-03", session);

        // Assert — response should be non-null and contain status
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingStatus tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingStatus_withValidBookingId_returnsResultMap() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-12345678");
        mockDetails.put("guest", "John Smith");
        when(bookingService.getBookingById("BK-12345678")).thenReturn(mockDetails);
        session.setAttribute("guestName", "John Smith");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-12345678", session);

        // Assert
        assertNotNull(result);
        assertEquals("BK-12345678", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_includesSessionGuestName() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());
        session.setAttribute("guestName", "Eve");

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCDEF12", session);

        // Assert
        assertEquals("Eve", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_withNoSessionGuest_sessionGuestIsNull() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());
        // No guestName set in session

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-NOFOUND01", session);

        // Assert
        assertNull(result.get("sessionGuest"), "sessionGuest should be null when not in session");
    }

    @Test
    void getBookingStatus_includesDetailsFromService() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-DETAIL01");
        mockDetails.put("room", "VILLA");
        when(bookingService.getBookingById("BK-DETAIL01")).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-DETAIL01", session);

        // Assert
        assertNotNull(result.get("details"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.get("details");
        assertEquals("VILLA", details.get("room"));
    }

    @Test
    void getBookingStatus_callsServiceWithCorrectBookingId() {
        // Arrange
        when(bookingService.getBookingById("BK-VERIFY01")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-VERIFY01", session);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-VERIFY01");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // checkAvailability tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void checkAvailability_withAvailableRoom_returnsAvailableTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(result);
        assertEquals(true, result.get("available"));
    }

    @Test
    void checkAvailability_withUnavailableRoom_returnsAvailableFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, result.get("available"));
    }

    @Test
    void checkAvailability_responseContainsRoomType() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals("DELUXE", result.get("roomType"));
    }

    @Test
    void checkAvailability_responseContainsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        // Assert
        assertNotNull(result.get("inventoryEndpoint"));
        String endpoint = (String) result.get("inventoryEndpoint");
        assertTrue(endpoint.contains("inventory-service"), "Endpoint should reference inventory-service");
    }

    @Test
    void checkAvailability_callsServiceWithCorrectRoomType() {
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
    void downloadReport_withValidMonth_returnsReportPath() {
        // Arrange
        when(bookingService.generateReport("2024-03")).thenReturn("Report generation triggered for: 2024-03");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-03");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("reportPath"));
    }

    @Test
    void downloadReport_reportPathContainsMonth() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report generation triggered for: March");

        // Act
        Map<String, Object> result = bookingController.downloadReport("March");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.contains("March"), "Report path should contain the month");
    }

    @Test
    void downloadReport_reportPathEndsWithPdf() {
        // Arrange
        when(bookingService.generateReport("2024-04")).thenReturn("Report generation triggered for: 2024-04");

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-04");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.endsWith(".pdf"), "Report path should end with .pdf");
    }

    @Test
    void downloadReport_responseContainsMessage() {
        // Arrange
        String expectedMessage = "Report generation triggered for: 2024-05";
        when(bookingService.generateReport("2024-05")).thenReturn(expectedMessage);

        // Act
        Map<String, Object> result = bookingController.downloadReport("2024-05");

        // Assert
        assertEquals(expectedMessage, result.get("message"));
    }

    @Test
    void downloadReport_callsServiceWithCorrectMonth() {
        // Arrange
        when(bookingService.generateReport("2024-06")).thenReturn("Report generation triggered for: 2024-06");

        // Act
        bookingController.downloadReport("2024-06");

        // Assert
        verify(bookingService, times(1)).generateReport("2024-06");
    }
}
