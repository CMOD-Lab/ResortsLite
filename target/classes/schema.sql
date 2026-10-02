-- Schema initialisation for ResortsLite H2 in-memory database
-- Executed automatically by Spring Boot on startup (spring.sql.init.mode=always by default for H2)

CREATE TABLE IF NOT EXISTS bookings (
    id       VARCHAR(50)  NOT NULL PRIMARY KEY,
    guest    VARCHAR(255) NOT NULL,
    room     VARCHAR(50)  NOT NULL,
    checkin  VARCHAR(20)  NOT NULL,
    checkout VARCHAR(20)  NOT NULL
);
