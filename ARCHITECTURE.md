# Ticket Booking System - Architecture

This document explains how our Ticket Booking System works under the hood. The system is designed to let users browse events, pick seats from a live seat map, hold them, confirm, cancel, and manage everything as an admin.

**Tech Stack**: Kotlin + Ktor (API), Exposed + HikariCP for the database layer, Supabase PostgreSQL, and a plain HTML/CSS/JS frontend with no complex build steps. We use an MVC architecture with a dedicated service layer between our controllers and repositories.

---

## 1. How the App is Structured

Our system follows a very clean Model-View-Controller (MVC) flow:

```
Browser (HTML/CSS/JS)
        │  fetch() + Authorization: Bearer <jwt>
        ▼
controller/   Ktor route handlers — these just parse requests and format responses.
        ▼
service/      All the heavy lifting: rules, validation, seat locking, and pricing.
        ▼
repository/   Data access layer only. It talks to PostgreSQL.
        ▼
Supabase PostgreSQL
```

We deliberately keep our `controller` layer thin. All the real business rules live in the `service` layer. This makes the logic incredibly easy to unit-test without having to spin up a real HTTP server or database. In fact, all of our service tests run against in-memory fakes!

### Frontend Structure
The frontend is pure HTML, CSS, and JS. We chose to avoid complex bundlers to keep things incredibly simple to reason about. 

Our core API logic lives in `Frontend/js/api.js`. It handles our JWT tokens by keeping them securely in `sessionStorage` (so a token dies as soon as you close the tab) and automatically clears your session if the backend returns a 401 Unauthorized.

---

## 2. The Booking Flow

Booking seats happens in two distinct steps:

1. **Hold**: `POST /api/events/{id}/hold` -> Creates a PENDING booking and locks the seats for 24 hours.
2. **Confirm**: `POST /api/bookings/{id}/confirm` -> Upgrades the booking to CONFIRMED.

```
PENDING ──confirm──> CONFIRMED ──cancel──> CANCELLED
   │                     │
   └──cancel──> CANCELLED└── (only allowed before the event starts)
   └──24h lapse / sweep──> EXPIRED
```

**Why two steps?** 
We issue the booking reference right away at the "Hold" stage so we don't have to change it on the user later. When they hit "Confirm", we actually re-check the locks to ensure the hold hasn't expired. We also have a background sweeper running every 5 minutes that hunts down and releases expired seat holds.

---

## 3. Database Design

### Why `event_seats` is separate from `seats`
The `seats` table represents the physical layout of the venue (Row A, Seat 1, etc.). The `event_seats` table tracks whether a seat is Available, Locked, or Booked for a *specific event*. When you create a new event, the system automatically generates an `event_seats` row for every physical seat in the venue.

### Double-Booking Prevention
This is the most critical part of the system! We have three layers of defense to guarantee a seat is never sold twice:

1. **Row Locking (`SELECT ... FOR UPDATE`)**: When a user asks for a seat, we lock that specific `event_seats` row. If someone else asks for it at the exact same millisecond, they have to wait in line.
2. **Status Check**: Once we have the lock, we check if the seat is still available. If it's taken, we return a nice `409 SEATS_UNAVAILABLE` error.
3. **Database Constraints**: As the ultimate fallback, our PostgreSQL table has a `UNIQUE` index that strictly enforces that a seat can only have one active claim at a time.

---

## 4. API Highlights

Here are some of the main endpoints. All payloads use JSON, and money is always handled as a decimal string (like `"1500.00"`) instead of a raw number to prevent floating-point rounding errors.

### Auth
- `POST /auth/register` - Create an account
- `POST /auth/login` - Get your JWT token
- `GET /auth/me` - Used to restore a session on page load

### Events (Public)
- `GET /events` - Search and filter events
- `GET /events/{id}/seats` - Get the live seat map and prices

### Bookings (Authenticated)
- `POST /events/{id}/hold` - Hold seats
- `POST /bookings/{id}/confirm` - Confirm a hold
- `GET /bookings/me` - See your history

### Admin
- `GET /admin/venues` and `GET /admin/events`
- `POST /admin/venues/{id}/seats` - Generate a venue's seat layout
- `GET /admin/bookings` - View all bookings across the platform

Admin routes are heavily protected. We use `authenticate(JWT_AUTH)` to ensure the user is logged in, and a `requireAdmin` guard to reject any normal users trying to sneak in!

---

## 5. Configuration

You can tweak the system by changing these environment variables (usually found in `application.conf` or your `.env` file):

- `HOLD_TTL_HOURS` (Default 24) - How long a user can hold a seat before paying.
- `HOLD_SWEEP_INTERVAL_MINUTES` (Default 5) - How often the server cleans up expired holds.
- `MAX_SEATS_PER_BOOKING` (Default 10) - Stop a single person from buying out the venue.

---

## 6. Testing

### Unit Tests
You can run our 284 unit tests without a database or server:
`.\gradlew.bat test`

### Manual & Stress Tests
We wrote a few PowerShell scripts in the `Backend/scripts/` folder to really put the system through its paces. 

- `smoke.ps1` runs through a complete user journey (registering, holding, booking, and cancelling).
- `concurrency-check.ps1` launches a coordinated attack that tries to buy the exact same seat at the exact same millisecond using two different clients. It always successfully rejects one of them!

---

## 7. Known Limitations
- **Seat availability isn't pushed live.** You have to refresh to see if a seat was taken by someone else while you were staring at it. WebSockets would be a great future addition!
- **No real payments.** We track the money, but we don't actually charge a credit card.
- **Unbounded requests.** There are no limits on how large a request body can be; this would normally be handled by a reverse proxy.
