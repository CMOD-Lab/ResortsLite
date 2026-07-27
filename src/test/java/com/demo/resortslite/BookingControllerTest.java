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

/**
 * Unit tests for {@link BookingController}.
 * BookingService is mocked; no Spring context is loaded.
 */
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
                "https://inventory-svc.internal/rooms");
        ReflectionTestUtils.setField(bookingController, "reportBasePath",
                "/tmp/reports/");
    }

    // -----------------------------------------------------------------------
    // createBooking()
    // -----------------------------------------------------------------------

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
                "Alice", "SUITE", "2024-06-01", "2024-06-05", httpSession);

        // Assert
        assertNotNull(response, "Response must not be null");
        assertEquals("confirmed", response.get("status"),
                "Status must be 'confirmed'");
    }

    @Test
    void createBooking_withValidParams_returnsBookingInResponse() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03", httpSession);

        // Assert
        assertNotNull(response.get("booking"),
                "Response must contain a 'booking' entry");
    }

    @Test
    void createBooking_delegatesToBookingService() {
        // Arrange
        when(bookingService.createBooking("Carol", "STANDARD", "2024-08-01", "2024-08-03"))
                .thenReturn(new HashMap<>());

        // Act
        bookingController.createBooking(
                "Carol", "STANDARD", "2024-08-01", "2024-08-03", httpSession);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Carol", "STANDARD", "2024-08-01", "2024-08-03");
    }

    @Test
    void createBooking_doesNotInteractWithSession() {
        // Cloud-compatibility: business state must NOT be stored in HTTP session
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

        bookingController.createBooking(
                "Dave", "VILLA", "2024-09-01", "2024-09-07", httpSession);

        // No setAttribute calls on the session
        verify(httpSession, never()).setAttribute(anyString(), any());
    }

    @Test
    void createBooking_responseContainsBothStatusAndBooking() {
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

        Map<String, Object> response = bookingController.createBooking(
                "Eve", "SUITE", "2024-10-01", "2024-10-04", httpSession);

        assertTrue(response.containsKey("status"), "Response must have 'status' key");
        assertTrue(response.containsKey("booking"), "Response must have 'booking' key");
    }

    // -----------------------------------------------------------------------
    // getBookingStatus()
    // -----------------------------------------------------------------------

    @Test
    void getBookingStatus_withValidId_returnsBookingIdInResponse() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("guest", "Frank");
        when(bookingService.getBookingById("BK-TEST01")).thenReturn(mockDetails);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus(
                "BK-TEST01", httpSession);

        // Assert
        assertNotNull(response);
        assertEquals("BK-TEST01", response.get("bookingId"),
                "Response must echo back the requested bookingId");
    }

    @Test
    void getBookingStatus_withValidId_returnsDetailsInResponse() {
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("guest", "Grace");
        when(bookingService.getBookingById("BK-TEST02")).thenReturn(mockDetails);

        Map<String, Object> response = bookingController.getBookingStatus(
                "BK-TEST02", httpSession);

        assertNotNull(response.get("details"),
                "Response must contain 'details'");
    }

    @Test
    void getBookingStatus_delegatesToBookingService() {
        when(bookingService.getBookingById("BK-TEST03")).thenReturn(new HashMap<>());

        bookingController.getBookingStatus("BK-TEST03", httpSession);

        verify(bookingService, times(1)).getBookingById("BK-TEST03");
    }

    @Test
    void getBookingStatus_doesNotReadFromSession() {
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        bookingController.getBookingStatus("BK-TEST04", httpSession);

        // Cloud-compatibility: must not read guestName from session
        verify(httpSession, never()).getAttribute(anyString());
    }

    // -----------------------------------------------------------------------
    // checkAvailability()
    // -----------------------------------------------------------------------

    @Test
    void checkAvailability_withAvailableRoom_returnsAvailableTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(response);
        assertEquals(true, response.get("available"),
                "available must be true for a valid room type");
    }

    @Test
    void checkAvailability_withUnavailableRoom_returnsAvailableFalse() {
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        Map<String, Object> response = bookingController.checkAvailability("PENTHOUSE");

        assertEquals(false, response.get("available"),
                "available must be false for an invalid room type");
    }

    @Test
    void checkAvailability_responseContainsRoomType() {
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        assertEquals("DELUXE", response.get("roomType"),
                "Response must echo back the requested roomType");
    }

    @Test
    void checkAvailability_responseContainsInventoryEndpoint() {
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        assertEquals("https://inventory-svc.internal/rooms",
                response.get("inventoryEndpoint"),
                "Response must include the injected inventory endpoint");
    }

    @Test
    void checkAvailability_inventoryEndpointUsesHttps() {
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        Map<String, Object> response = bookingController.checkAvailability("VILLA");

        String endpoint = (String) response.get("inventoryEndpoint");
        assertNotNull(endpoint);
        assertTrue(endpoint.startsWith("https://"),
                "Inventory endpoint must use HTTPS");
    }

    // -----------------------------------------------------------------------
    // downloadReport()
    // -----------------------------------------------------------------------

    @Test
    void downloadReport_returnsReportPathInResponse() {
        // Arrange
        when(bookingService.generateReport("2024-03")).thenReturn("Report generated");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-03");

        // Assert
        assertNotNull(response);
        assertNotNull(response.get("reportPath"),
                "Response must contain 'reportPath'");
    }

    @Test
    void downloadReport_reportPathContainsMonth() {
        when(bookingService.generateReport("2024-04")).thenReturn("Report generated");

        Map<String, Object> response = bookingController.downloadReport("2024-04");

        String path = (String) response.get("reportPath");
        assertTrue(path.contains("2024-04"),
                "reportPath must contain the requested month");
    }

    @Test
    void downloadReport_reportPathContainsPdfSuffix() {
        when(bookingService.generateReport("2024-05")).thenReturn("Report generated");

        Map<String, Object> response = bookingController.downloadReport("2024-05");

        String path = (String) response.get("reportPath");
        assertTrue(path.endsWith("_bookings.pdf"),
                "reportPath must end with '_bookings.pdf'");
    }

    @Test
    void downloadReport_reportPathUsesInjectedBasePath() {
        when(bookingService.generateReport("2024-06")).thenReturn("Report generated");

        Map<String, Object> response = bookingController.downloadReport("2024-06");

        String path = (String) response.get("reportPath");
        assertTrue(path.startsWith("/tmp/reports/"),
                "reportPath must start with the injected base path");
    }

    @Test
    void downloadReport_responseContainsMessage() {
        when(bookingService.generateReport("2024-07")).thenReturn("Report generated for 2024-07");

        Map<String, Object> response = bookingController.downloadReport("2024-07");

        assertNotNull(response.get("message"),
                "Response must contain a 'message' key");
    }

    @Test
    void downloadReport_delegatesToBookingService() {
        when(bookingService.generateReport("2024-08")).thenReturn("ok");

        bookingController.downloadReport("2024-08");

        verify(bookingService, times(1)).generateReport("2024-08");
    }
}
