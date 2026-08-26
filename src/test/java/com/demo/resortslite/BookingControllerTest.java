package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
        // Inject @Value fields via ReflectionTestUtils (Spring context not loaded)
        ReflectionTestUtils.setField(bookingController,
                "inventoryEndpoint",
                "https://inventory-svc.internal:8081/rooms");
        ReflectionTestUtils.setField(bookingController,
                "reportBasePath",
                "/tmp/reports");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // createBooking tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        Map<String, Object> mockBooking = Map.of(
                "bookingId", "BK-12345678",
                "guestName", "Alice",
                "roomType", "SUITE",
                "checkIn", "2024-06-01",
                "checkOut", "2024-06-05",
                "confirmationCode", "abc123");
        when(bookingService.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05"))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_responseContainsBookingObject() {
        // Arrange
        Map<String, Object> mockBooking = Map.of("bookingId", "BK-ABCDEF01");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        assertTrue(response.containsKey("booking"), "Response must contain 'booking' key");
        assertNotNull(response.get("booking"));
    }

    @Test
    void createBooking_delegatesToBookingService() {
        // Arrange
        when(bookingService.createBooking("Carol", "STANDARD", "2024-08-01", "2024-08-02"))
                .thenReturn(Map.of());

        // Act
        bookingController.createBooking("Carol", "STANDARD", "2024-08-01", "2024-08-02");

        // Assert
        verify(bookingService, times(1))
                .createBooking("Carol", "STANDARD", "2024-08-01", "2024-08-02");
    }

    @Test
    void createBooking_bookingObjectMatchesMockReturn() {
        // Arrange
        Map<String, Object> mockBooking = Map.of("bookingId", "BK-XYZ99999", "guestName", "Dave");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Dave", "VILLA", "2024-09-01", "2024-09-07");

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> booking = (Map<String, Object>) response.get("booking");
        assertEquals("BK-XYZ99999", booking.get("bookingId"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getBookingStatus tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getBookingStatus_withValidId_returnsMapWithBookingId() {
        // Arrange
        when(bookingService.getBookingById("BK-001")).thenReturn(Map.of("id", "BK-001"));

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-001");

        // Assert
        assertNotNull(response);
        assertEquals("BK-001", response.get("bookingId"));
    }

    @Test
    void getBookingStatus_responseContainsDetailsKey() {
        // Arrange
        when(bookingService.getBookingById("BK-002")).thenReturn(Map.of("guest", "Eve"));

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-002");

        // Assert
        assertTrue(response.containsKey("details"), "Response must contain 'details' key");
    }

    @Test
    void getBookingStatus_detailsMatchServiceReturn() {
        // Arrange
        Map<String, Object> serviceResult = Map.of("guest", "Frank", "room", "SUITE");
        when(bookingService.getBookingById("BK-003")).thenReturn(serviceResult);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-003");

        // Assert
        assertEquals(serviceResult, response.get("details"));
    }

    @Test
    void getBookingStatus_delegatesToBookingService() {
        // Arrange
        when(bookingService.getBookingById("BK-004")).thenReturn(Map.of());

        // Act
        bookingController.getBookingStatus("BK-004");

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-004");
    }

    @Test
    void getBookingStatus_whenServiceReturnsError_responseStillContainsBookingId() {
        // Arrange
        when(bookingService.getBookingById("BK-NOTFOUND"))
                .thenReturn(Map.of("error", "Booking not found: BK-NOTFOUND"));

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-NOTFOUND");

        // Assert
        assertEquals("BK-NOTFOUND", response.get("bookingId"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // checkAvailability tests
    // ─────────────────────────────────────────────────────────────────────────

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
        assertNotNull(response.get("inventoryEndpoint"));
        assertTrue(((String) response.get("inventoryEndpoint")).startsWith("https://"),
                "Inventory endpoint must use HTTPS");
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

    // ─────────────────────────────────────────────────────────────────────────
    // downloadReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void downloadReport_responseContainsReportPath() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        // Act
        Map<String, Object> response = bookingController.downloadReport("March");

        // Assert
        assertNotNull(response);
        assertTrue(response.containsKey("reportPath"), "Response must contain 'reportPath'");
    }

    @Test
    void downloadReport_reportPathContainsMonth() {
        // Arrange
        when(bookingService.generateReport("June")).thenReturn("Report generated for June");

        // Act
        Map<String, Object> response = bookingController.downloadReport("June");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.contains("June"), "Report path must contain the month");
    }

    @Test
    void downloadReport_reportPathEndsWithPdf() {
        // Arrange
        when(bookingService.generateReport("July")).thenReturn("Report generated for July");

        // Act
        Map<String, Object> response = bookingController.downloadReport("July");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.endsWith(".pdf"), "Report path must end with .pdf");
    }

    @Test
    void downloadReport_responseContainsMessage() {
        // Arrange
        when(bookingService.generateReport("August")).thenReturn("Report generated for August");

        // Act
        Map<String, Object> response = bookingController.downloadReport("August");

        // Assert
        assertTrue(response.containsKey("message"), "Response must contain 'message'");
        assertEquals("Report generated for August", response.get("message"));
    }

    @Test
    void downloadReport_delegatesToBookingService() {
        // Arrange
        when(bookingService.generateReport("September")).thenReturn("ok");

        // Act
        bookingController.downloadReport("September");

        // Assert
        verify(bookingService, times(1)).generateReport("September");
    }

    @Test
    void downloadReport_reportPathUsesConfiguredBasePath() {
        // Arrange
        when(bookingService.generateReport("October")).thenReturn("ok");

        // Act
        Map<String, Object> response = bookingController.downloadReport("October");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.startsWith("/tmp/reports"),
                "Report path must start with the configured base path");
    }
}
