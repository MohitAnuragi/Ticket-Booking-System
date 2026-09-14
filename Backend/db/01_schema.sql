-- ============================================================================
-- Ticket Booking System - base schema
-- Source: implementation_plan.md section 5.2 (reproduced verbatim)
--
-- HOW TO RUN: paste this whole file into the Supabase SQL Editor and execute,
-- THEN run 02_deltas.sql. Both are required before the backend will start.
--
-- gen_random_uuid() is available by default on Supabase (pgcrypto/pg_catalog).
-- ============================================================================

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    email VARCHAR(160) UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER', -- USER | ADMIN
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE venues (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(160) NOT NULL,
    address TEXT,
    city VARCHAR(100) NOT NULL,
    total_capacity INT NOT NULL
);

CREATE TABLE seats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id UUID NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    seat_row VARCHAR(5) NOT NULL,
    seat_number INT NOT NULL,
    seat_type VARCHAR(20) NOT NULL DEFAULT 'REGULAR', -- REGULAR | PREMIUM | VIP
    price_multiplier NUMERIC(4,2) NOT NULL DEFAULT 1.0,
    UNIQUE (venue_id, seat_row, seat_number)
);

CREATE TABLE events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id UUID NOT NULL REFERENCES venues(id),
    title VARCHAR(200) NOT NULL,
    description TEXT,
    category VARCHAR(60),
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    base_price NUMERIC(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED', -- DRAFT | PUBLISHED | CANCELLED
    poster_url TEXT
);

CREATE TABLE event_seats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    seat_id UUID NOT NULL REFERENCES seats(id),
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', -- AVAILABLE | LOCKED | BOOKED
    version INT NOT NULL DEFAULT 0,                  -- optimistic locking
    UNIQUE (event_id, seat_id)
);

CREATE TABLE bookings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    event_id UUID NOT NULL REFERENCES events(id),
    booking_reference VARCHAR(20) UNIQUE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED', -- PENDING | CONFIRMED | CANCELLED
    total_amount NUMERIC(10,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE booking_seats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    event_seat_id UUID NOT NULL REFERENCES event_seats(id),
    price NUMERIC(10,2) NOT NULL,
    UNIQUE (event_seat_id) -- replaced by a partial index in 02_deltas.sql
);
