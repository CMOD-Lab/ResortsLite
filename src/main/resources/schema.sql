-- PostgreSQL schema initialisation for ResortsLite
-- Migrated from H2/MySQL to PostgreSQL 16
-- Uses IF NOT EXISTS to support idempotent re-runs (spring.sql.init.mode=always)

CREATE TABLE IF NOT EXISTS bookings (
    id          VARCHAR(50)     PRIMARY KEY,
    guest       VARCHAR(255)    NOT NULL,
    room        VARCHAR(50)     NOT NULL,
    checkin     DATE            NOT NULL,
    checkout    DATE            NOT NULL,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW()
);
