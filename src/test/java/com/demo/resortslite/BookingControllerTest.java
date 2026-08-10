package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @Mock
    private HttpSession httpSession;

    @InjectMocks
    private BookingController bookingController;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bookingController, "inventoryEndpoint",
                "https://inventory-svc.internal:8081/rooms");
        ReflectionTestUtils.setField(bookingController, "reportBasePath",
                "/tmp/reports/");
    }

    // ─── createBooking ────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-12345678");
        mockBooking.put("guestName", "Alice");
        when(bookingService.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05"))
                .thenReturn(mockBooking);

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
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCDEF12");
        mockBooking.put("guestName", "Bob");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03", httpSession);

        // Assert
        assertNotNull(response.get("booking"));
        assertEquals(mockBooking, response.get("booking"));
    }

    @Test
    void createBooking_setsLastBookingInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-SESSION01");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking(
                "Carol", "STANDARD", "2024-08-01", "2024-08-02", httpSession);

        // Assert
        verify(httpSession).setAttribute("lastBooking", mockBooking);
    }

    @Test
    void createBooking_setsGuestNameInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking(
                "Dave", "VILLA", "2024-09-01", "2024-09-10", httpSession);

        // Assert
        verify(httpSession).setAttribute("guestName", "Dave");
    }

    @Test
    void createBooking_callsBookingServiceCreateBooking() {
        // Arrange
        when(bookingService.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-05"))
                .thenReturn(new HashMap<>());

        // Act
        bookingController.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-05", httpSession);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Eve", "SUITE", "2024-10-01", "2024-10-05");
    }

    @Test
    void createBooking_responseContainsBothStatusAndBooking() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-KEYS0001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Frank", "DELUXE", "2024-11-01", "2024-11-03", httpSession);

        // Assert
        assertTrue(response.containsKey("status"));
        assertTrue(response.containsKey("booking"));
    }

    // ─── getBookingStatus ─────────────────────────────────────────────────────

    @Test
    void getBookingStatus_returnsBookingIdInResponse() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn("Alice");
        when(bookingService.getBookingById("BK-001")).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-001", httpSession);

        // Assert
        assertEquals("BK-001", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_returnsSessionGuestFromSession() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn("Bob");
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-002", httpSession);

        // Assert
        assertEquals("Bob", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_whenNoSessionGuest_returnsNullSessionGuest() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn(null);
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-003", httpSession);

        // Assert
        assertNull(result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_returnsDetailsFromBookingService() {
        // Arrange
        Map<String, Object> details = new HashMap<>();
        details.put("guest", "Carol");
        details.put("room", "SUITE");
        when(httpSession.getAttribute("guestName")).thenReturn("Carol");
        when(bookingService.getBookingById("BK-004")).thenReturn(details);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-004", httpSession);

        // Assert
        assertEquals(details, result.get("details"));
    }

    @Test
    void getBookingStatus_callsGetBookingByIdOnService() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn("Dave");
        when(bookingService.getBookingById("BK-005")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-005", httpSession);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-005");
    }

    @Test
    void getBookingStatus_responseContainsAllThreeKeys() {
        // Arrange
        when(httpSession.getAttribute("guestName")).thenReturn("Eve");
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-006", httpSession);

        // Assert
        assertTrue(result.containsKey("bookingId"));
        assertTrue(result.containsKey("sessionGuest"));
        assertTrue(result.containsKey("details"));
    }

    // ─── checkAvailability ────────────────────────────────────────────────────

    @Test
    void checkAvailability_returnsRoomTypeInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertEquals("SUITE", result.get("roomType"));
    }

    @Test
    void checkAvailability_returnsInventoryEndpointInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals("https://inventory-svc.internal:8081/rooms",
                result.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_whenRoomAvailable_returnsTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        // Assert
        assertEquals(true, result.get("available"));
    }

    @Test
    void checkAvailability_whenRoomNotAvailable_returnsFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("UNKNOWN")).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("UNKNOWN");

        // Assert
        assertEquals(false, result.get("available"));
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
    void checkAvailability_responseContainsAllThreeKeys() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertTrue(result.containsKey("roomType"));
        assertTrue(result.containsKey("inventoryEndpoint"));
        assertTrue(result.containsKey("available"));
    }

    // ─── downloadReport ───────────────────────────────────────────────────────

    @Test
    void downloadReport_returnsReportPathContainingMonth() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report for March");

        // Act
        Map<String, Object> result = bookingController.downloadReport("March");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertNotNull(reportPath);
        assertTrue(reportPath.contains("March"));
    }

    @Test
    void downloadReport_reportPathEndsWithBookingsPdf() {
        // Arrange
        when(bookingService.generateReport("April")).thenReturn("Report for April");

        // Act
        Map<String, Object> result = bookingController.downloadReport("April");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.endsWith("_bookings.pdf"));
    }

    @Test
    void downloadReport_reportPathStartsWithBasePath() {
        // Arrange
        when(bookingService.generateReport("May")).thenReturn("Report for May");

        // Act
        Map<String, Object> result = bookingController.downloadReport("May");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.startsWith("/tmp/reports/"));
    }

    @Test
    void downloadReport_returnsMessageFromBookingService() {
        // Arrange
        when(bookingService.generateReport("June")).thenReturn("Report generated for June");

        // Act
        Map<String, Object> result = bookingController.downloadReport("June");

        // Assert
        assertEquals("Report generated for June", result.get("message"));
    }

    @Test
    void downloadReport_callsGenerateReportOnService() {
        // Arrange
        when(bookingService.generateReport("July")).thenReturn("Report for July");

        // Act
        bookingController.downloadReport("July");

        // Assert
        verify(bookingService, times(1)).generateReport("July");
    }

    @Test
    void downloadReport_responseContainsBothReportPathAndMessage() {
        // Arrange
        when(bookingService.generateReport("August")).thenReturn("Report for August");

        // Act
        Map<String, Object> result = bookingController.downloadReport("August");

        // Assert
        assertTrue(result.containsKey("reportPath"));
        assertTrue(result.containsKey("message"));
    }
}
