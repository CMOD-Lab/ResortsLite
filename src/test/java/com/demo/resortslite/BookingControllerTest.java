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
                "https://inventory-svc.internal:8081/rooms");
    }

    // -----------------------------------------------------------------------
    // createBooking tests
    // -----------------------------------------------------------------------

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
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Jane Doe", "DELUXE", "2024-04-01", "2024-04-03");

        // Assert
        assertNotNull(response.get("booking"));
        assertEquals(mockBooking, response.get("booking"));
    }

    @Test
    void createBooking_callsBookingService() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        when(bookingService.createBooking("Alice", "STANDARD", "2024-05-01", "2024-05-02"))
                .thenReturn(mockBooking);

        // Act
        bookingController.createBooking("Alice", "STANDARD", "2024-05-01", "2024-05-02");

        // Assert
        verify(bookingService, times(1))
                .createBooking("Alice", "STANDARD", "2024-05-01", "2024-05-02");
    }

    @Test
    void createBooking_responseContainsStatusAndBookingKeys() {
        // Arrange
        Map<String, Object> mockBooking = new HashMap<>();
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Bob", "VILLA", "2024-06-01", "2024-06-07");

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
        mockDetails.put("guest", "John Smith");
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
        mockDetails.put("id", "BK-12345678");
        when(bookingService.getBookingById("BK-12345678")).thenReturn(mockDetails);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-12345678");

        // Assert
        assertEquals(mockDetails, response.get("details"));
    }

    @Test
    void getBookingStatus_callsBookingService() {
        // Arrange
        when(bookingService.getBookingById("BK-TESTID")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-TESTID");

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-TESTID");
    }

    @Test
    void getBookingStatus_responseContainsBookingIdAndDetailsKeys() {
        // Arrange
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("BK-ANY");

        // Assert
        assertTrue(response.containsKey("bookingId"));
        assertTrue(response.containsKey("details"));
    }

    @Test
    void getBookingStatus_withErrorBookingId_returnsErrorDetails() {
        // Arrange
        Map<String, Object> errorMap = new HashMap<>();
        errorMap.put("error", "Booking not found: INVALID");
        when(bookingService.getBookingById("INVALID")).thenReturn(errorMap);

        // Act
        Map<String, Object> response = bookingController.getBookingStatus("INVALID");

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
        assertEquals("https://inventory-svc.internal:8081/rooms", response.get("inventoryEndpoint"));
    }

    @Test
    void checkAvailability_callsBookingService() {
        // Arrange
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        // Act
        bookingController.checkAvailability("VILLA");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("VILLA");
    }

    @Test
    void checkAvailability_responseContainsAllRequiredKeys() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        // Assert
        assertTrue(response.containsKey("roomType"));
        assertTrue(response.containsKey("inventoryEndpoint"));
        assertTrue(response.containsKey("available"));
    }

    // -----------------------------------------------------------------------
    // downloadReport tests
    // -----------------------------------------------------------------------

    @Test
    void downloadReport_withValidMonth_returnsMessageInResponse() {
        // Arrange
        when(bookingService.generateReport("March"))
                .thenReturn("Report generation triggered for: March via http://payment-svc.internal:9090/charge");

        // Act
        Map<String, Object> response = bookingController.downloadReport("March");

        // Assert
        assertNotNull(response);
        assertNotNull(response.get("message"));
    }

    @Test
    void downloadReport_callsBookingService() {
        // Arrange
        when(bookingService.generateReport("April")).thenReturn("Report triggered for: April");

        // Act
        bookingController.downloadReport("April");

        // Assert
        verify(bookingService, times(1)).generateReport("April");
    }

    @Test
    void downloadReport_responseContainsMessageKey() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("some message");

        // Act
        Map<String, Object> response = bookingController.downloadReport("January");

        // Assert
        assertTrue(response.containsKey("message"));
    }

    @Test
    void downloadReport_messageMatchesServiceReturn() {
        // Arrange
        String expectedMessage = "Report generation triggered for: May via http://payment-svc.internal:9090/charge";
        when(bookingService.generateReport("May")).thenReturn(expectedMessage);

        // Act
        Map<String, Object> response = bookingController.downloadReport("May");

        // Assert
        assertEquals(expectedMessage, response.get("message"));
    }
}
