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

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bookingController, "inventoryEndpoint",
                "https://inventory-svc/rooms");
        ReflectionTestUtils.setField(bookingController, "reportBasePath",
                "/tmp/reports/");
    }

    // ─── createBooking ────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-12345678");
        mockBooking.put("guestName", "John Smith");
        mockBooking.put("roomType", "SUITE");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> result = bookingController.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05");

        // Assert
        assertNotNull(result);
        assertEquals("confirmed", result.get("status"));
    }

    @Test
    void createBooking_withValidParams_returnsBookingInResponse() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCDEF12");
        mockBooking.put("guestName", "Jane Doe");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> result = bookingController.createBooking(
                "Jane Doe", "DELUXE", "2024-04-01", "2024-04-03");

        // Assert
        assertNotNull(result.get("booking"));
        @SuppressWarnings("unchecked")
        Map<String, Object> booking = (Map<String, Object>) result.get("booking");
        assertEquals("BK-ABCDEF12", booking.get("bookingId"));
    }

    @Test
    void createBooking_delegatesToBookingService() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        when(bookingService.createBooking("Alice", "VILLA", "2024-05-01", "2024-05-10"))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Alice", "VILLA", "2024-05-01", "2024-05-10");

        // Assert
        verify(bookingService, times(1))
                .createBooking("Alice", "VILLA", "2024-05-01", "2024-05-10");
    }

    @Test
    void createBooking_responseContainsStatusAndBookingKeys() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> result = bookingController.createBooking(
                "Bob", "STANDARD", "2024-06-01", "2024-06-02");

        // Assert
        assertTrue(result.containsKey("status"), "Response should contain 'status' key");
        assertTrue(result.containsKey("booking"), "Response should contain 'booking' key");
    }

    // ─── getBookingStatus ─────────────────────────────────────────────────────

    @Test
    void getBookingStatus_withValidId_returnsBookingIdInResponse() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-12345678");
        mockDetails.put("guest", "John Smith");
        when(bookingService.getBookingById("BK-12345678")).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-12345678");

        // Assert
        assertNotNull(result);
        assertEquals("BK-12345678", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_withValidId_returnsDetailsInResponse() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-ABCDEF12");
        mockDetails.put("guest", "Jane Doe");
        when(bookingService.getBookingById("BK-ABCDEF12")).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCDEF12");

        // Assert
        assertNotNull(result.get("details"));
    }

    @Test
    void getBookingStatus_delegatesToBookingService() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById("BK-TEST1234")).thenReturn(mockDetails);

        // Act
        bookingController.getBookingStatus("BK-TEST1234");

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-TEST1234");
    }

    @Test
    void getBookingStatus_responseContainsBookingIdAndDetailsKeys() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ANYID");

        // Assert
        assertTrue(result.containsKey("bookingId"), "Response should contain 'bookingId' key");
        assertTrue(result.containsKey("details"), "Response should contain 'details' key");
    }

    @Test
    void getBookingStatus_whenBookingNotFound_returnsErrorDetails() {
        // Arrange
        Map<String, Object> errorDetails = new HashMap<>();
        errorDetails.put("error", "Booking not found: BK-MISSING");
        when(bookingService.getBookingById("BK-MISSING")).thenReturn(errorDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-MISSING");

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.get("details");
        assertTrue(details.containsKey("error"));
    }

    // ─── checkAvailability ────────────────────────────────────────────────────

    @Test
    void checkAvailability_withValidRoomType_returnsRoomTypeInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(result);
        assertEquals("SUITE", result.get("roomType"));
    }

    @Test
    void checkAvailability_withAvailableRoom_returnsTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

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
    void checkAvailability_responseContainsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        // Assert
        assertNotNull(result.get("inventoryEndpoint"));
        assertEquals("https://inventory-svc/rooms", result.get("inventoryEndpoint"));
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
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("STANDARD");

        // Assert
        assertTrue(result.containsKey("roomType"));
        assertTrue(result.containsKey("available"));
        assertTrue(result.containsKey("inventoryEndpoint"));
    }

    // ─── downloadReport ───────────────────────────────────────────────────────

    @Test
    void downloadReport_withMonth_returnsReportPathInResponse() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        // Act
        Map<String, Object> result = bookingController.downloadReport("March");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("reportPath"));
    }

    @Test
    void downloadReport_reportPathContainsMonthAndExtension() {
        // Arrange
        when(bookingService.generateReport("April")).thenReturn("Report generated for April");

        // Act
        Map<String, Object> result = bookingController.downloadReport("April");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.contains("April"), "Report path should contain the month");
        assertTrue(reportPath.endsWith("_bookings.pdf"), "Report path should end with '_bookings.pdf'");
    }

    @Test
    void downloadReport_reportPathUsesConfiguredBasePath() {
        // Arrange
        when(bookingService.generateReport("May")).thenReturn("Report generated for May");

        // Act
        Map<String, Object> result = bookingController.downloadReport("May");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.startsWith("/tmp/reports/"),
                "Report path should start with configured base path");
    }

    @Test
    void downloadReport_responseContainsMessage() {
        // Arrange
        when(bookingService.generateReport("June")).thenReturn("Report generated for June");

        // Act
        Map<String, Object> result = bookingController.downloadReport("June");

        // Assert
        assertNotNull(result.get("message"));
        assertEquals("Report generated for June", result.get("message"));
    }

    @Test
    void downloadReport_delegatesToBookingService() {
        // Arrange
        when(bookingService.generateReport("July")).thenReturn("Report generated for July");

        // Act
        bookingController.downloadReport("July");

        // Assert
        verify(bookingService, times(1)).generateReport("July");
    }

    @Test
    void downloadReport_responseContainsReportPathAndMessageKeys() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("Report generated");

        // Act
        Map<String, Object> result = bookingController.downloadReport("August");

        // Assert
        assertTrue(result.containsKey("reportPath"), "Response should contain 'reportPath' key");
        assertTrue(result.containsKey("message"), "Response should contain 'message' key");
    }
}
