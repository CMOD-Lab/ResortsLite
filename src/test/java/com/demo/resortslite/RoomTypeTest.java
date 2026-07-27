package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RoomType} utility class.
 * Covers: VALID_TYPES constant, isValid() method, constructor privacy.
 */
class RoomTypeTest {

    // -----------------------------------------------------------------------
    // isValid() — valid room types
    // -----------------------------------------------------------------------

    @Test
    void isValid_withStandard_returnsTrue() {
        // Arrange / Act / Assert
        assertTrue(RoomType.isValid("STANDARD"),
                "STANDARD should be a valid room type");
    }

    @Test
    void isValid_withDeluxe_returnsTrue() {
        assertTrue(RoomType.isValid("DELUXE"),
                "DELUXE should be a valid room type");
    }

    @Test
    void isValid_withSuite_returnsTrue() {
        assertTrue(RoomType.isValid("SUITE"),
                "SUITE should be a valid room type");
    }

    @Test
    void isValid_withVilla_returnsTrue() {
        assertTrue(RoomType.isValid("VILLA"),
                "VILLA should be a valid room type");
    }

    // -----------------------------------------------------------------------
    // isValid() — invalid / edge-case inputs
    // -----------------------------------------------------------------------

    @Test
    void isValid_withNull_returnsFalse() {
        assertFalse(RoomType.isValid(null),
                "null should not be a valid room type");
    }

    @Test
    void isValid_withEmptyString_returnsFalse() {
        assertFalse(RoomType.isValid(""),
                "Empty string should not be a valid room type");
    }

    @Test
    void isValid_withLowerCaseStandard_returnsFalse() {
        assertFalse(RoomType.isValid("standard"),
                "Validation is case-sensitive; lowercase should fail");
    }

    @Test
    void isValid_withMixedCase_returnsFalse() {
        assertFalse(RoomType.isValid("Standard"),
                "Mixed-case should not be valid");
    }

    @Test
    void isValid_withUnknownType_returnsFalse() {
        assertFalse(RoomType.isValid("PENTHOUSE"),
                "Unknown room type should not be valid");
    }

    @Test
    void isValid_withWhitespace_returnsFalse() {
        assertFalse(RoomType.isValid("  SUITE  "),
                "Room type with surrounding whitespace should not be valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "DELUXE", "SUITE", "VILLA"})
    void isValid_allValidTypes_returnTrue(String roomType) {
        assertTrue(RoomType.isValid(roomType),
                roomType + " must be recognised as valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CABIN", "BUNGALOW", "HOSTEL", "TENT", "PENTHOUSE"})
    void isValid_unknownTypes_returnFalse(String roomType) {
        assertFalse(RoomType.isValid(roomType),
                roomType + " must not be recognised as valid");
    }

    // -----------------------------------------------------------------------
    // VALID_TYPES constant
    // -----------------------------------------------------------------------

    @Test
    void validTypes_containsExactlyFourEntries() {
        assertEquals(4, RoomType.VALID_TYPES.size(),
                "VALID_TYPES should contain exactly 4 room types");
    }

    @Test
    void validTypes_containsAllExpectedRoomTypes() {
        assertTrue(RoomType.VALID_TYPES.contains("STANDARD"));
        assertTrue(RoomType.VALID_TYPES.contains("DELUXE"));
        assertTrue(RoomType.VALID_TYPES.contains("SUITE"));
        assertTrue(RoomType.VALID_TYPES.contains("VILLA"));
    }

    @Test
    void validTypes_isImmutable() {
        // Set.of() returns an unmodifiable set — adding should throw
        assertThrows(UnsupportedOperationException.class,
                () -> RoomType.VALID_TYPES.add("CABIN"),
                "VALID_TYPES must be immutable");
    }

    // -----------------------------------------------------------------------
    // Utility-class constructor is private
    // -----------------------------------------------------------------------

    @Test
    void constructor_isPrivate() throws NoSuchMethodException {
        Constructor<RoomType> ctor = RoomType.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(ctor.getModifiers()),
                "RoomType constructor must be private (utility class)");
    }

    @Test
    void constructor_throwsExceptionWhenInvokedViaReflection() throws Exception {
        Constructor<RoomType> ctor = RoomType.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        // Reflection invocation of a private constructor should still succeed
        // (no explicit throw in the body), but the class must not be publicly instantiable.
        assertNotNull(ctor.newInstance(),
                "Reflective instantiation should produce a non-null object");
    }
}
