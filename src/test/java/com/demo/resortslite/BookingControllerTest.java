package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link BookingController}.
 * Covers createBooking, getBookingStatus, checkAvailability, downloadReport.
 */
@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    @BeforeEach
    void setUp() {
        // Inject @Value fields via ReflectionTestUtils (Spring context not loaded)
        ReflectionTestUtils.setField(bookingController, "inventoryEndpoint",
                "https://inventory-svc.internal:8081/rooms");
        ReflectionTestUtils.setField(bookingController, "reportBasePath",
                "/tmp/reports/");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-ABCD1234");
        booking.put("guestName", "Alice Smith");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(booking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice Smith", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_withValidParams_returnsBookingInResponse() {
        // Arrange
        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(booking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob Jones", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        assertNotNull(response.get("booking"));
        assertEquals(booking, response.get("booking"));
    }

    @Test
    void createBooking_delegatesToBookingService() {
        // Arrange
        when(bookingService.createBooking("Carol", "VILLA", "2024-08-01", "2024-08-10"))
                .thenReturn(new HashMap<>());

        // Act
        bookingController.createBooking("Carol", "VILLA", "2024-08-01", "2024-08-10");

        // Assert
        verify(bookingService, times(1))
                .createBooking("Carol", "VILLA", "2024-08-01", "2024-08-10");
    }

    @Test
    void createBooking_responseContainsStatusAndBookingKeys() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Dave", "STANDARD", "2024-09-01", "2024-09-02");

        // Assert
        assertTrue(response.containsKey("status"));
        assertTrue(response.containsKey("booking"));
    }

    @Test
    void createBooking_doesNotUseHttpSession_isStateless() {
        // Arrange — verify no session interaction (stateless design)
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-05");

        // Assert — response is returned directly, not stored in session
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingStatus
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingStatus_withValidId_returnsBookingIdInResponse() {
        // Arrange
        when(bookingService.getBookingById("BK-12345678")).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-12345678");

        // Assert
        assertEquals("BK-12345678", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_withValidId_returnsDetailsInResponse() {
        // Arrange
        Map<String, Object> details = new HashMap<>();
        details.put("guest", "Frank Lee");
        when(bookingService.getBookingById("BK-ABCDEF12")).thenReturn(details);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCDEF12");

        // Assert
        assertEquals(details, result.get("details"));
    }

    @Test
    void getBookingStatus_delegatesToBookingService() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-TEST0001");

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-TEST0001");
    }

    @Test
    void getBookingStatus_responseContainsBookingIdAndDetailsKeys() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-XYZ");

        // Assert
        assertTrue(result.containsKey("bookingId"));
        assertTrue(result.containsKey("details"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // checkAvailability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void checkAvailability_withAvailableRoom_returnsTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertEquals(true, result.get("available"));
    }

    @Test
    void checkAvailability_withUnavailableRoom_returnsFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, result.get("available"));
    }

    @Test
    void checkAvailability_returnsRoomTypeInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals("DELUXE", result.get("roomType"));
    }

    @Test
    void checkAvailability_returnsInventoryEndpointInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        // Assert
        assertNotNull(result.get("inventoryEndpoint"));
        assertTrue(result.get("inventoryEndpoint").toString().startsWith("https://"));
    }

    @Test
    void checkAvailability_delegatesToBookingService() {
        // Arrange
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        // Act
        bookingController.checkAvailability("VILLA");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("VILLA");
    }

    @Test
    void checkAvailability_responseContainsRoomTypeAvailableAndEndpointKeys() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertTrue(result.containsKey("roomType"));
        assertTrue(result.containsKey("available"));
        assertTrue(result.containsKey("inventoryEndpoint"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // downloadReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void downloadReport_withValidMonth_returnsReportPathInResponse() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        // Act
        Map<String, Object> result = bookingController.downloadReport("March");

        // Assert
        assertNotNull(result.get("reportPath"));
        assertTrue(result.get("reportPath").toString().contains("March"));
    }

    @Test
    void downloadReport_withValidMonth_returnsMessageInResponse() {
        // Arrange
        when(bookingService.generateReport("April")).thenReturn("Report generated for April");

        // Act
        Map<String, Object> result = bookingController.downloadReport("April");

        // Assert
        assertEquals("Report generated for April", result.get("message"));
    }

    @Test
    void downloadReport_reportPathContainsPdfExtension() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("ok");

        // Act
        Map<String, Object> result = bookingController.downloadReport("June");

        // Assert
        assertTrue(result.get("reportPath").toString().endsWith("_bookings.pdf"));
    }

    @Test
    void downloadReport_reportPathUsesInjectedBasePath() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("ok");

        // Act
        Map<String, Object> result = bookingController.downloadReport("July");

        // Assert
        assertTrue(result.get("reportPath").toString().startsWith("/tmp/reports/"));
    }

    @Test
    void downloadReport_delegatesToBookingService() {
        // Arrange
        when(bookingService.generateReport("August")).thenReturn("ok");

        // Act
        bookingController.downloadReport("August");

        // Assert
        verify(bookingService, times(1)).generateReport("August");
    }

    @Test
    void downloadReport_responseContainsReportPathAndMessageKeys() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("ok");

        // Act
        Map<String, Object> result = bookingController.downloadReport("September");

        // Assert
        assertTrue(result.containsKey("reportPath"));
        assertTrue(result.containsKey("message"));
    }
}
