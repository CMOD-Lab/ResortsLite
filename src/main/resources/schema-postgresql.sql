-- PostgreSQL 16 schema initialisation for ResortsLite
-- This script is executed automatically by Spring Boot on startup
-- when spring.sql.init.mode=always and spring.sql.init.platform=postgresql.

-- Create the bookings table if it does not already exist.
-- Uses PostgreSQL-native types: TEXT (variable-length string), TIMESTAMP WITH TIME ZONE.
CREATE TABLE IF NOT EXISTS bookings (
    id          TEXT        NOT NULL,
    guest       TEXT        NOT NULL,
    room        TEXT        NOT NULL,
    checkin     TEXT        NOT NULL,
    checkout    TEXT        NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT pk_bookings PRIMARY KEY (id)
);

-- Index on guest name for faster lookup queries
CREATE INDEX IF NOT EXISTS idx_bookings_guest ON bookings (guest);
