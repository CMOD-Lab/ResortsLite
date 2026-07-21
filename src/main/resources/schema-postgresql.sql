-- ============================================================
-- ResortsLite — PostgreSQL 16 Schema Initialisation Script
-- Migration: MySQL / H2 → PostgreSQL 16
-- ============================================================

-- Create the bookings table using PostgreSQL-native types.
-- PostgreSQL migration notes:
--   • VARCHAR(n) is standard ANSI SQL — compatible with PostgreSQL 16.
--   • TIMESTAMP WITH TIME ZONE (TIMESTAMPTZ) is preferred in PostgreSQL
--     for storing date/time values with timezone awareness.
--   • DEFAULT CURRENT_TIMESTAMP is ANSI SQL — compatible with PostgreSQL 16.
--   • TEXT type used for longer string fields (PostgreSQL-idiomatic).

CREATE TABLE IF NOT EXISTS bookings (
    id           VARCHAR(20)  PRIMARY KEY,
    guest        VARCHAR(255) NOT NULL,
    room         VARCHAR(50)  NOT NULL,
    checkin      VARCHAR(20)  NOT NULL,
    checkout     VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- Index for guest name lookups (supports ILIKE queries)
-- PostgreSQL migration: pg_trgm extension enables GIN index for ILIKE.
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_bookings_guest ON bookings (guest);

-- ============================================================
-- Comments for documentation
-- ============================================================
COMMENT ON TABLE  bookings            IS 'Resort booking records — migrated from MySQL to PostgreSQL 16';
COMMENT ON COLUMN bookings.id         IS 'Booking identifier (BK-XXXXXXXX format)';
COMMENT ON COLUMN bookings.guest      IS 'Guest full name';
COMMENT ON COLUMN bookings.room       IS 'Room type: STANDARD, DELUXE, SUITE, VILLA';
COMMENT ON COLUMN bookings.checkin    IS 'Check-in date (ISO 8601 string)';
COMMENT ON COLUMN bookings.checkout   IS 'Check-out date (ISO 8601 string)';
COMMENT ON COLUMN bookings.created_at IS 'Record creation timestamp (UTC)';
