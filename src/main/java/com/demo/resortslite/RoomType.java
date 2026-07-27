package com.demo.resortslite;

import java.util.Set;

/**
 * Centralised room-type constants and validation helper.
 *
 * <p>Extracted from {@link BookingService} to eliminate duplicated room-type validation
 * logic that previously appeared in both {@code calculateRoomPrice} and
 * {@code isRoomAvailable} (resolves dup-logic-001).
 *
 * <p>Adding a new room type requires a single change here; all callers automatically
 * pick up the updated set.
 */
public final class RoomType {

    /** All valid room type identifiers. */
    public static final Set<String> VALID_TYPES = Set.of(
            "STANDARD",
            "DELUXE",
            "SUITE",
            "VILLA"
    );

    private RoomType() {
        // Utility class — do not instantiate.
    }

    /**
     * Returns {@code true} if {@code roomType} is a recognised room type.
     *
     * @param roomType the room type string to validate (case-sensitive)
     * @return {@code true} if valid; {@code false} otherwise
     */
    public static boolean isValid(String roomType) {
        return roomType != null && VALID_TYPES.contains(roomType);
    }
}
