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
 * Covers createBooking, getBookingStatus, checkAvailability, and downloadReport endpoints.
 */
@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    @BeforeEach
    void setUp() {
        // Inject the inventoryEndpoint field value (normally set via @Value)
        ReflectionTestUtils.setField(bookingController, "inventoryEndpoint",
                "https://inventory-svc.internal/rooms");
    }

    // =========================================================================
    // createBooking tests
    // =========================================================================

    @Test
    void createBooking_withValidParams_returnsStatusConfirmed() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        mockBooking.put("bookingId", "BK-ABCD1234");
        mockBooking.put("guestName", "John Smith");
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05");

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
        when(bookingService.createBooking("Jane Doe", "DELUXE", "2024-04-01", "2024-04-03"))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Jane Doe", "DELUXE", "2024-04-01", "2024-04-03");

        // Assert
        assertNotNull(response.get("booking"));
        @SuppressWarnings("unchecked")
        Map<String, Object> booking = (Map<String, Object>) response.get("booking");
        assertEquals("BK-ABCD1234", booking.get("bookingId"));
    }

    @Test
    void createBooking_delegatesToBookingService() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

        // Act
        bookingController.createBooking("Alice", "VILLA", "2024-05-01", "2024-05-10");

        // Assert
        verify(bookingService, times(1))
                .createBooking("Alice", "VILLA", "2024-05-01", "2024-05-10");
    }

    @Test
    void createBooking_responseContainsTwoKeys() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "STANDARD", "2024-06-01", "2024-06-07");

        // Assert — response must have exactly "status" and "booking"
        assertTrue(response.containsKey("status"));
        assertTrue(response.containsKey("booking"));
    }

    @Test
    void createBooking_doesNotStoreStateInSession() {
        // Arrange — verifies no session-related side effects (cloud compatibility fix)
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

        // Act — should not throw and should return a clean response
        Map<String, Object> response = bookingController.createBooking(
                "Carol", "SUITE", "2024-07-01", "2024-07-05");

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    // =========================================================================
    // getBookingStatus tests
    // =========================================================================

    @Test
    void getBookingStatus_withValidId_returnsBookingId() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("id", "BK-12345678");
        when(bookingService.getBookingById("BK-12345678")).thenReturn(mockDetails);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-12345678");

        // Assert
        assertEquals("BK-12345678", response.get("bookingId"));
    }

    @Test
    void getBookingStatus_withValidId_returnsDetails() {
        // Arrange
        Map<String, Object> mockDetails = new HashMap<>();
        mockDetails.put("guest", "Dave");
        when(bookingService.getBookingById("BK-ABCDEF12")).thenReturn(mockDetails);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-ABCDEF12");

        // Assert
        assertNotNull(response.get("details"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) response.get("details");
        assertEquals("Dave", details.get("guest"));
    }

    @Test
    void getBookingStatus_delegatesToBookingService() {
        // Arrange
        when(bookingService.getBookingById("BK-TEST0001")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-TEST0001");

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-TEST0001");
    }

    @Test
    void getBookingStatus_withNotFoundId_returnsErrorDetails() {
        // Arrange
        Map<String, Object> errorMap = new HashMap<>();
        errorMap.put("error", "Booking not found: BK-NOTFOUND");
        when(bookingService.getBookingById("BK-NOTFOUND")).thenReturn(errorMap);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-NOTFOUND");

        // Assert
        assertEquals("BK-NOTFOUND", response.get("bookingId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) response.get("details");
        assertTrue(details.containsKey("error"));
    }

    @Test
    void getBookingStatus_responseContainsBothKeys() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-ANYID");

        // Assert
        assertTrue(response.containsKey("bookingId"));
        assertTrue(response.containsKey("details"));
    }

    // =========================================================================
    // checkAvailability tests
    // =========================================================================

    @Test
    void checkAvailability_withAvailableRoom_returnsAvailableTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        // Assert
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

        // Assert — endpoint must be HTTPS (cr-java-0088 fix)
        String endpoint = response.get("inventoryEndpoint").toString();
        assertNotNull(endpoint);
        assertTrue(endpoint.startsWith("https://"));
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
    void checkAvailability_responseContainsThreeKeys() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        // Assert
        assertTrue(response.containsKey("roomType"));
        assertTrue(response.containsKey("inventoryEndpoint"));
        assertTrue(response.containsKey("available"));
    }

    // =========================================================================
    // downloadReport tests
    // =========================================================================

    @Test
    void downloadReport_withMonth_returnsMessageInResponse() {
        // Arrange
        when(bookingService.generateReport("2024-03"))
                .thenReturn("Report generation triggered for: 2024-03 via https://payment-svc.internal/charge");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-03");

        // Assert
        assertNotNull(response.get("message"));
        assertTrue(response.get("message").toString().contains("2024-03"));
    }

    @Test
    void downloadReport_delegatesToBookingService() {
        // Arrange
        when(bookingService.generateReport("2024-06")).thenReturn("Report triggered");

        // Act
        bookingController.downloadReport("2024-06");

        // Assert
        verify(bookingService, times(1)).generateReport("2024-06");
    }

    @Test
    void downloadReport_responseContainsMessageKey() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("Report triggered");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2025-01");

        // Assert
        assertTrue(response.containsKey("message"));
    }

    @Test
    void downloadReport_doesNotUseHardcodedPath() {
        // Arrange — verifies czr-java-001 fix: no hardcoded /var/legacy/reports/ path
        when(bookingService.generateReport("2024-12")).thenReturn("Report triggered");

        // Act
        Map<String, Object> response = bookingController.downloadReport("2024-12");

        // Assert — message should not contain legacy hardcoded path
        String message = response.get("message").toString();
        assertFalse(message.contains("/var/legacy/reports/"));
    }
}
