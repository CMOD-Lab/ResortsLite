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
                "http://inventory-svc.internal:8081/rooms");
        ReflectionTestUtils.setField(bookingController, "reportBasePath",
                "/tmp/reports/");
    }

    // -----------------------------------------------------------------------
    // createBooking tests
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-12345678");
        mockBooking.put("guestName", "John Smith");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "John Smith", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_withValidParams_returnsBookingInResponse() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCDEF12");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Jane Doe", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        assertNotNull(response.get("booking"));
        assertEquals(mockBooking, response.get("booking"));
    }

    @Test
    void createBooking_delegatesToBookingService() {
        // Arrange
        when(bookingService.createBooking("Alice", "STANDARD", "2024-08-01", "2024-08-02"))
                .thenReturn(new HashMap<>());

        // Act
        bookingController.createBooking("Alice", "STANDARD", "2024-08-01", "2024-08-02");

        // Assert
        verify(bookingService, times(1))
                .createBooking("Alice", "STANDARD", "2024-08-01", "2024-08-02");
    }

    @Test
    void createBooking_responseContainsStatusAndBookingKeys() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "VILLA", "2024-09-01", "2024-09-10");

        // Assert
        assertTrue(response.containsKey("status"));
        assertTrue(response.containsKey("booking"));
    }

    // -----------------------------------------------------------------------
    // getBookingStatus tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingStatus_withValidId_returnsBookingIdInResponse() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-12345678");
        when(bookingService.getBookingById("BK-12345678")).thenReturn(mockDetails);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-12345678");

        // Assert
        assertNotNull(response);
        assertEquals("BK-12345678", response.get("bookingId"));
    }

    @Test
    void getBookingStatus_withValidId_returnsDetailsInResponse() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("guest", "John Smith");
        when(bookingService.getBookingById("BK-ABCDEF12")).thenReturn(mockDetails);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-ABCDEF12");

        // Assert
        assertEquals(mockDetails, response.get("details"));
    }

    @Test
    void getBookingStatus_delegatesToBookingService() {
        // Arrange
        when(bookingService.getBookingById("BK-TEST")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-TEST");

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-TEST");
    }

    @Test
    void getBookingStatus_responseContainsBookingIdAndDetailsKeys() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-XYZ");

        // Assert
        assertTrue(response.containsKey("bookingId"));
        assertTrue(response.containsKey("details"));
    }

    @Test
    void getBookingStatus_whenBookingNotFound_returnsErrorDetails() {
        // Arrange
        Map<String, Object> errorMap = new HashMap<>();
        errorMap.put("error", "Booking not found: BK-MISSING");
        when(bookingService.getBookingById("BK-MISSING")).thenReturn(errorMap);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-MISSING");

        // Assert
        Map<?, ?> details = (Map<?, ?>) response.get("details");
        assertTrue(details.containsKey("error"));
    }

    // -----------------------------------------------------------------------
    // checkAvailability tests
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
    void checkAvailability_returnsRoomTypeInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals("DELUXE", response.get("roomType"));
    }

    @Test
    void checkAvailability_returnsInventoryEndpointInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        // Assert
        assertEquals("http://inventory-svc.internal:8081/rooms", response.get("inventoryEndpoint"));
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
    void checkAvailability_responseContainsAllExpectedKeys() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        // Assert
        assertTrue(response.containsKey("roomType"));
        assertTrue(response.containsKey("inventoryEndpoint"));
        assertTrue(response.containsKey("available"));
    }

    // -----------------------------------------------------------------------
    // downloadReport tests
    // -----------------------------------------------------------------------

    @Test
    void downloadReport_withMonth_returnsReportPathInResponse() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        // Act
        Map<String, Object> response = bookingController.downloadReport("March");

        // Assert
        assertNotNull(response);
        String reportPath = (String) response.get("reportPath");
        assertNotNull(reportPath);
        assertTrue(reportPath.contains("March_bookings.pdf"));
    }

    @Test
    void downloadReport_withMonth_returnsMessageInResponse() {
        // Arrange
        when(bookingService.generateReport("April")).thenReturn("Report generated for April");

        // Act
        Map<String, Object> response = bookingController.downloadReport("April");

        // Assert
        assertEquals("Report generated for April", response.get("message"));
    }

    @Test
    void downloadReport_reportPathUsesConfiguredBasePath() {
        // Arrange
        when(bookingService.generateReport("June")).thenReturn("Report generated");

        // Act
        Map<String, Object> response = bookingController.downloadReport("June");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.startsWith("/tmp/reports/"));
    }

    @Test
    void downloadReport_delegatesToBookingService() {
        // Arrange
        when(bookingService.generateReport("July")).thenReturn("Report generated");

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
        Map<String, Object> response = bookingController.downloadReport("August");

        // Assert
        assertTrue(response.containsKey("reportPath"));
        assertTrue(response.containsKey("message"));
    }

    @Test
    void downloadReport_withCustomBasePath_usesInjectedPath() {
        // Arrange
        ReflectionTestUtils.setField(bookingController, "reportBasePath", "/custom/reports/");
        when(bookingService.generateReport("September")).thenReturn("Report generated");

        // Act
        Map<String, Object> response = bookingController.downloadReport("September");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.startsWith("/custom/reports/"));
    }
}
