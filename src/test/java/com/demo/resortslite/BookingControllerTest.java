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

    // -----------------------------------------------------------------------
    // createBooking tests
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
    void createBooking_withValidParams_returnsBookingInResponse() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "Bob");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03", session);

        // Assert
        assertNotNull(response.get("booking"));
        assertEquals(mockBooking, response.get("booking"));
    }

    @Test
    void createBooking_storesLastBookingInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-SESS001");
        mockBooking.put("guestName", "Carol");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Carol", "STANDARD", "2024-08-01", "2024-08-03", session);

        // Assert
        assertNotNull(session.getAttribute("lastBooking"));
        assertEquals(mockBooking, session.getAttribute("lastBooking"));
    }

    @Test
    void createBooking_storesGuestNameInSession() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-SESS002");
        mockBooking.put("guestName", "Dave");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Dave", "VILLA", "2024-09-01", "2024-09-07", session);

        // Assert
        assertEquals("Dave", session.getAttribute("guestName"));
    }

    @Test
    void createBooking_callsBookingServiceOnce() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-CALL001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-04", session);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Eve", "SUITE", "2024-10-01", "2024-10-04");
    }

    @Test
    void createBooking_responseContainsStatusAndBookingKeys() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-KEYS001");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Frank", "DELUXE", "2024-11-01", "2024-11-05", session);

        // Assert
        assertTrue(response.containsKey("status"));
        assertTrue(response.containsKey("booking"));
    }

    // -----------------------------------------------------------------------
    // getBookingStatus tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingStatus_withValidBookingId_returnsResultMap() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-STATUS01");
        mockDetails.put("guest", "Grace");
        when(bookingService.getBookingById("BK-STATUS01")).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-STATUS01", session);

        // Assert
        assertNotNull(result);
        assertEquals("BK-STATUS01", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_includesDetailsFromService() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-DET001");
        mockDetails.put("guest", "Henry");
        when(bookingService.getBookingById("BK-DET001")).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-DET001", session);

        // Assert
        assertEquals(mockDetails, result.get("details"));
    }

    @Test
    void getBookingStatus_withNoSessionGuest_returnsNullSessionGuest() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-NOGUEST", session);

        // Assert
        assertNull(result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_withSessionGuest_returnsSessionGuestName() {
        // Arrange
        session.setAttribute("guestName", "Iris");
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-IRIS001", session);

        // Assert
        assertEquals("Iris", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_callsBookingServiceWithCorrectId() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById("BK-VERIFY1")).thenReturn(mockDetails);

        // Act
        bookingController.getBookingStatus("BK-VERIFY1", session);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-VERIFY1");
    }

    @Test
    void getBookingStatus_responseContainsRequiredKeys() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        when(bookingService.getBookingById(anyString())).thenReturn(mockDetails);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-KEYS001", session);

        // Assert
        assertTrue(result.containsKey("bookingId"));
        assertTrue(result.containsKey("sessionGuest"));
        assertTrue(result.containsKey("details"));
    }

    // -----------------------------------------------------------------------
    // checkAvailability tests
    // -----------------------------------------------------------------------

    @Test
    void checkAvailability_withValidRoomType_returnsResponseMap() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(response);
    }

    @Test
    void checkAvailability_withAvailableRoom_returnsTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals(true, response.get("available"));
    }

    @Test
    void checkAvailability_withUnavailableRoom_returnsFalse() {
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
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        // Assert
        assertEquals("STANDARD", response.get("roomType"));
    }

    @Test
    void checkAvailability_responseContainsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("VILLA");

        // Assert
        assertNotNull(response.get("inventoryEndpoint"));
        assertTrue(((String) response.get("inventoryEndpoint")).contains("inventory-service"));
    }

    @Test
    void checkAvailability_callsBookingServiceWithCorrectRoomType() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        bookingController.checkAvailability("SUITE");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("SUITE");
    }

    // -----------------------------------------------------------------------
    // downloadReport tests
    // -----------------------------------------------------------------------

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
        when(bookingService.generateReport("April")).thenReturn("Report for April");

        // Act
        Map<String, Object> response = bookingController.downloadReport("April");

        // Assert
        assertNotNull(response.get("reportPath"));
        assertTrue(((String) response.get("reportPath")).contains("April"));
    }

    @Test
    void downloadReport_responseContainsMessage() {
        // Arrange
        when(bookingService.generateReport("May")).thenReturn("Report for May");

        // Act
        Map<String, Object> response = bookingController.downloadReport("May");

        // Assert
        assertEquals("Report for May", response.get("message"));
    }

    @Test
    void downloadReport_reportPathContainsPdfExtension() {
        // Arrange
        when(bookingService.generateReport("June")).thenReturn("Report for June");

        // Act
        Map<String, Object> response = bookingController.downloadReport("June");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.endsWith(".pdf"), "Report path should end with .pdf");
    }

    @Test
    void downloadReport_callsBookingServiceGenerateReport() {
        // Arrange
        when(bookingService.generateReport("July")).thenReturn("Report for July");

        // Act
        bookingController.downloadReport("July");

        // Assert
        verify(bookingService, times(1)).generateReport("July");
    }

    @Test
    void downloadReport_reportPathContainsLegacyReportsDirectory() {
        // Arrange
        when(bookingService.generateReport("August")).thenReturn("Report for August");

        // Act
        Map<String, Object> response = bookingController.downloadReport("August");

        // Assert
        String reportPath = (String) response.get("reportPath");
        assertTrue(reportPath.contains("/var/legacy/reports/"));
    }
}
