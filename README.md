# Ticket Booking System

A ticket booking platform: browse events, pick seats from a live seat map, hold them,
confirm, cancel, and manage everything as an admin.

**Stack** — Kotlin 2.4.20 + Ktor 3.5.2 (API) · Exposed 1.5 + HikariCP · Supabase
PostgreSQL · plain HTML/CSS/JS frontend (no build step).
**Architecture** — MVC, with a service layer between controllers and repositories.

| Part | State |
|---|---|
| Backend API | Feature-complete, 284 tests passing (265 service + 19 HTTP) |
| Database schema | `Backend/db/01_schema.sql` + `02_deltas.sql`, applied by hand |
| Frontend | All pages built; not yet exercised in a browser |

Build progress and the resume point live in [`PROGRESS.md`](PROGRESS.md); the original
design document is [`implementation_plan.md`](implementation_plan.md).

**New here?** [`SETUP_GUIDE.md`](SETUP_GUIDE.md) is the step-by-step version: which
Supabase credentials to change, how to run everything, and how to test it.

---

## 1. Quick start

### 1.1 Database

Create a Supabase project, then run these two scripts **in order** in the SQL Editor:

1. `Backend/db/01_schema.sql` — the seven tables.
2. `Backend/db/02_deltas.sql` — required corrections (see §4.2). Idempotent.

### 1.2 Backend

```powershell
$env:JAVA_HOME   = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$env:DB_URL      = "jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require"
$env:DB_USER     = "postgres"
$env:DB_PASSWORD = "<supabase database password>"
$env:JWT_SECRET  = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))

# Optional: creates an ADMIN account on boot, since public registration only makes USERs.
$env:ADMIN_BOOTSTRAP_EMAIL    = "admin@example.com"
$env:ADMIN_BOOTSTRAP_PASSWORD = "<at least 8 characters>"

cd Backend
.\gradlew.bat run     # http://localhost:8080
```

Use the **direct connection on port 5432**, not the 6543 pooler. Supabase's
transaction-mode pgbouncer does not support server-side prepared statements, which
Exposed relies on; it produces intermittent "prepared statement already exists"
errors. If you must use 6543, append `&prepareThreshold=0`.

Startup is fail-fast by design: a missing setting, an unreachable database or a
schema that does not match what the code expects stops the process at boot with an
actionable message, rather than failing the first request that happens to need it.

Check it came up:

```powershell
curl http://localhost:8080/api/health
# {"status":"UP","database":"connected"}
```

`database` reflects a real `SELECT 1`, and the endpoint returns 503 when the database
is unreachable, so monitoring cannot read a broken deployment as healthy.

### 1.3 Frontend

No build step — serve `Frontend/` with any static server and open it in a browser:

```powershell
cd Frontend
# any static server works; the pages are plain HTML + ES modules
```

The API root is the first constant in `Frontend/js/api.js`
(`export const API_BASE_URL = 'http://localhost:8080/api'`). Change it there if the
backend runs elsewhere.

Because the pages use ES modules, they must be served over `http://`, not opened as
`file://` — module imports are blocked by the file-origin policy. Once served, set
`CORS_ALLOWED_HOSTS` to that origin (for example `http://localhost:3000`) instead of
relying on the any-origin development default.

Every page is now built: the event listing, auth, the seat map, hold/confirm, booking
history with cancellation, and the four admin screens.

A page that needs a signed-in visitor calls `requireSignIn()` from `js/session.js`,
which re-checks the token against `GET /auth/me` rather than trusting the cached user —
a token the server has since rejected is then discovered on load instead of when the
visitor tries to book. It redirects to `login.html?next=<page>`, and `next` is accepted
only as a same-origin relative `.html` path, so the parameter cannot be turned into an
open redirect to a lookalike sign-in page.

---

## 2. Architecture

```
Browser (HTML/CSS/JS)
        │  fetch() + Authorization: Bearer <jwt>
        ▼
controller/   Ktor route handlers — parse, delegate, respond. No business logic.
        ▼
service/      All rules: validation, seat locking, pricing, auth, cancellation windows.
        ▼
repository/   Data access only (interface + Postgres implementation). No rules.
        ▼
Supabase PostgreSQL
```

**MVC mapping**
- **Model** — `model/` (Exposed tables + entities) and `repository/`. Data and access, no rules.
- **View** — the static frontend. Presentation only; it never touches the database.
- **Controller** — `controller/`. Receives HTTP, delegates, formats the response.

Business logic sits in `service/` rather than in the controllers. That is a deliberate
refinement of classic MVC: controllers stay thin and every rule is unit-testable
without HTTP. All 284 tests run against in-memory fake repositories, with no database
and no server.

```
Backend/src/main/kotlin/com/ticketbooking/
├── Application.kt              entry point, module wiring
├── plugins/                    DatabaseFactory, Security, StatusPages, Routing,
│                               Serialization, Monitoring/CORS, SchemaVerifier,
│                               Components (manual DI), HoldSweeping
├── model/                      tables + entities (User, Venue, Seat, Event,
│                               EventSeat, Booking, BookingSeat)
├── dto/                        request/response shapes (kotlinx.serialization)
├── repository/                 interfaces + impl/Postgres*
├── service/                    Auth, Event, Seat, Venue, EventAdmin, Booking,
│                               AdminBooking, HoldSweeper
├── controller/                 Auth, Event, Booking, Admin
└── util/                       JwtUtil, PasswordHasher, Validators, TimeProvider,
                                ApiException
```

Dependencies are wired by hand in `plugins/Components.kt`. A DI framework would add a
dependency and some indirection for very little benefit at this size; doing it
manually keeps the whole object graph readable in one file.

```
Frontend/
├── index.html        event listing with search and filters
├── event.html        event detail + seat map + seat selection
├── booking.html      hold review, confirm, release
├── my-bookings.html  booking history + cancel
├── login.html        sign in
├── register.html     create an account
├── admin/            dashboard, manage-venues, manage-events, manage-bookings
│                     (each page's entry script sits beside it; admin-common.js
│                      holds the ADMIN guard and shared form helpers)
├── css/styles.css    one stylesheet; Grid for card/seat layouts, Flexbox for bars
└── js/
    ├── api.js        the ONLY module that knows the base URL, the token and the
    │                 error envelope; every page imports named functions from it
    ├── session.js    route guards, and the validated ?next= redirect
    ├── nav.js        shared header/footer shell, rendered rather than duplicated
    ├── format.js     money and date formatting; exact money sums in integer paise
    ├── events.js     the listing page
    ├── seatmap.js    the seat map and selection state
    ├── booking.js    hold review and confirmation
    ├── bookings.js   booking history and cancellation
    └── auth.js       both auth pages, switched by the form's data-mode
```

`api.js` keeps the JWT in `sessionStorage` (plan §7): scoped to the tab and cleared
when it closes, so a token cannot outlive the session on a shared machine. A 401 on an
authenticated call clears the session, so a stale token cannot leave every page
rendering as signed-in.

---

## 3. The booking flow

Booking is two steps, not one:

```
POST /api/events/{id}/hold        -> 201, a PENDING booking, seats LOCKED for 24h
POST /api/bookings/{id}/confirm   -> 200, the booking becomes CONFIRMED
```

A hold **is** a booking in `PENDING` state, reusing the status the schema already
defines rather than adding a separate holds table — so holds inherit the same
pricing, seat linkage and history as confirmed bookings.

```
PENDING ──confirm──> CONFIRMED ──cancel──> CANCELLED
   │                     │
   └──cancel──> CANCELLED└── (only before start_time)
   └──24h lapse / sweep──> EXPIRED
```

Notes on deliberate choices:

- **The booking reference is issued at hold time**, not at confirmation.
  `booking_reference` is `NOT NULL`, so a PENDING hold needs one anyway, and
  regenerating it at confirm would change the code the user was already shown.
- **Confirmation re-checks everything.** Between hold and confirm the hold may have
  lapsed and another user may legitimately have taken the seats, so confirm re-locks
  the rows and re-verifies before committing.
- **Confirm and cancel are idempotent.** A double-clicked button or a retried request
  is harmless.
- **Expiry is handled twice, on purpose.** The seat map reports a lapsed hold as
  `AVAILABLE` immediately (`EventSeat.effectiveStatus`), and a background sweeper
  rewrites the rows every 5 minutes. The lazy read keeps the UI honest between
  sweeps; the sweeper is what guarantees a seat nobody looks at again is still
  released.

### Cancellation window

A customer may cancel a `CONFIRMED` booking only **before** the event's `start_time`
(plan §8); after that they get `409 EVENT_ALREADY_STARTED`. A `PENDING` hold can
always be released, since freeing seats is never harmful. An admin is not bound by
the window at all — a rescheduled event has to be releasable.

---

## 4. Database

### 4.1 Why `event_seats` is separate from `seats`

`seats` is the venue's fixed physical layout (row, number, tier), created once per
venue. `event_seats` is a per-event row tracking that seat's status
(`AVAILABLE` / `LOCKED` / `BOOKED`) **for that event only**. So the same physical
seat can be free for Friday's show and sold for Saturday's, without duplicating the
layout. Creating an event automatically generates one `event_seats` row per venue
seat, inside the same transaction — an event with an empty seat map would be silently
unbookable, so the step cannot be forgotten.

### 4.2 Two corrections to the original schema

Both are in `db/02_deltas.sql`, with the reasoning inline.

1. **Lock ownership and expiry.** The base schema has an `event_seats.status` of
   `LOCKED` but no way to record *who* holds the lock or *when* it lapses, so a lock
   could never be released. Added `locked_by`, `lock_expires_at` (and
   `bookings.hold_expires_at`) as `TIMESTAMPTZ` — a 24-hour deadline compared against
   `now()` in a timezone-naive column would expire holds at the wrong moment.

2. **`UNIQUE(event_seat_id)` on `booking_seats` broke cancel-then-rebook.** Cancelling
   returns seats to `AVAILABLE`, but the original `booking_seats` row still occupied
   that `event_seat_id`, so the seat could never be sold again. Replaced with

   ```sql
   CREATE UNIQUE INDEX uq_booking_seats_active
       ON booking_seats (event_seat_id) WHERE is_active;
   ```

   plus an `is_active` flag. This keeps the database-level guarantee — at most one
   *active* claim per seat — while letting a seat be resold and preserving cancelled
   rows as history. Cancellation flips `is_active` to false rather than deleting, so a
   cancelled booking still shows which seats it had.

### 4.3 Double-booking prevention

Three layers, in order of authority:

1. **`SELECT ... FOR UPDATE`** takes a row lock on each requested `event_seats` row.
   A second transaction wanting the same seat blocks until the first finishes, so the
   availability check cannot go stale between reading and writing. Rows are locked in
   a deterministic order (by id) to avoid deadlocks between overlapping requests.
2. **The status check inside that lock** rejects seats already held or sold, returning
   `409 SEATS_UNAVAILABLE` with the exact offending ids so the UI can grey them out.
3. **`uq_booking_seats_active` is the final arbiter.** Even if the application logic
   were wrong, PostgreSQL refuses a second active claim and the transaction rolls back.

Layer 3 is what makes the guarantee real; layers 1 and 2 exist to turn a raw
constraint violation into a clean, actionable API response. Row locking is a database
behaviour and cannot be proven by unit test — verify it by hand with §7.2.

---

## 5. API reference

Base URL `/api`. All bodies are JSON. Money is a **decimal string** (`"1500.00"`), never
a JSON number: binary floating point cannot represent every decimal exactly, and money
that silently rounds is a real defect. Timestamps are ISO-8601 and treated as UTC.

### 5.1 Auth

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/auth/register` | — | Create an account (always role `USER`) |
| POST | `/auth/login` | — | Exchange credentials for a JWT |
| GET | `/auth/me` | user | The current account; restores a session on page load |

```jsonc
// POST /api/auth/register
{ "name": "Mohit", "email": "mohit@example.com", "password": "secret123" }
// 201
{ "id": "…", "name": "Mohit", "email": "mohit@example.com", "role": "USER" }

// POST /api/auth/login
{ "email": "mohit@example.com", "password": "secret123" }
// 200
{ "token": "eyJ…", "expiresIn": 86400,
  "user": { "id": "…", "name": "Mohit", "email": "mohit@example.com", "role": "USER" } }
```

Passwords are BCrypt-hashed. The JWT carries `userId` and `role` and is valid 24h.
`UserResponse` has no password field at all — that type is the reason a hash cannot
leak into a response by accident.

### 5.2 Events (public)

| Method | Path | Description |
|---|---|---|
| GET | `/events` | List/search/filter: `?search=&city=&category=&date=&upcomingOnly=` |
| GET | `/events/filters` | Distinct cities and categories, for populating dropdowns |
| GET | `/events/{id}` | Event detail with venue |
| GET | `/events/{id}/seats` | Seat map with per-seat status and resolved price |

Only `PUBLISHED` events are visible here; drafts are admin-only. `date` is `yyyy-MM-dd`
and matches the whole day.

```jsonc
// GET /api/events/{id}/seats -> 200
{
  "eventId": "…",
  "seats": [
    { "eventSeatId": "…", "row": "A", "number": 1, "type": "PREMIUM",
      "price": "2250.00", "status": "AVAILABLE" },
    { "eventSeatId": "…", "row": "A", "number": 2, "type": "PREMIUM",
      "price": "2250.00", "status": "BOOKED" }
  ],
  "summary": { "total": 120, "available": 118, "locked": 1, "booked": 1 }
}
```

`eventSeatId` is what the client sends back when holding — the per-event row, not the
physical seat. `price` is the event's base price × the seat tier's multiplier, resolved
server-side so the frontend never computes money.

### 5.3 Bookings (user)

| Method | Path | Description |
|---|---|---|
| POST | `/events/{id}/hold` | Hold seats for 24h → a `PENDING` booking |
| POST | `/bookings/{id}/confirm` | Confirm the hold |
| GET | `/bookings/me` | Booking history, newest first |
| GET | `/bookings/{id}` | One booking (own only) |
| DELETE | `/bookings/{id}` | Cancel a hold or a confirmed booking |

```jsonc
// POST /api/events/{id}/hold
{ "eventSeatIds": ["…", "…"] }
// 201
{ "id": "…", "bookingReference": "TB-8F2K91", "status": "PENDING",
  "totalAmount": "4500.00", "createdAt": "2026-09-11T12:00:00",
  "expiresAt": "2026-09-12T12:00:00Z",
  "event": { "id": "…", "title": "Coldplay Live", "startTime": "2026-10-01T19:00:00",
             "venueName": "NSCI Dome", "venueCity": "Mumbai" },
  "seats": [ { "eventSeatId": "…", "label": "A1", "row": "A", "number": 1,
               "type": "PREMIUM", "price": "2250.00" } ],
  "seatLabels": ["A1", "A2"] }

// 409 if a seat was taken first
{ "error": "SEATS_UNAVAILABLE",
  "message": "These seats are no longer available: A1",
  "unavailableSeats": ["…"] }
```

`expiresAt` is present only while `status` is `PENDING`. A booking may contain at most
10 seats (`MAX_SEATS_PER_BOOKING`), so one user cannot take a whole venue. Duplicate
seat ids in one request are rejected rather than silently de-duplicated — quietly
charging for one seat would hide a confused client.

`GET /bookings/me` is read-only: a hold that has lapsed but not yet been swept still
reports `PENDING` with a past `expiresAt`, rather than having a GET mutate data.
Compare `expiresAt` against now to render it as lapsed.

### 5.4 Admin (role `ADMIN`)

| Method | Path | Description |
|---|---|---|
| GET | `/admin/venues` | List venues with real seat counts |
| POST | `/admin/venues` | Create a venue |
| GET | `/admin/venues/{id}` | Venue detail |
| POST | `/admin/venues/{id}/seats` | Bulk-generate a seat layout |
| GET | `/admin/events` | List events, including `DRAFT` and `CANCELLED` |
| GET | `/admin/events/{id}` | Event detail, including drafts |
| POST | `/admin/events` | Create an event (auto-generates `event_seats`) |
| PUT | `/admin/events/{id}` | Patch an event; omitted fields keep their value |
| DELETE | `/admin/events/{id}` | Cancel the event (soft delete) |
| GET | `/admin/bookings` | All bookings: `?eventId=&userId=&status=&limit=&offset=` |
| GET | `/admin/bookings/{id}` | Any booking, with the customer's details |
| DELETE | `/admin/bookings/{id}` | Cancel any user's booking |

```jsonc
// POST /api/admin/venues/{id}/seats — call repeatedly to build tiers
{ "rows": ["A","B","C"], "seatsPerRow": 10, "seatType": "PREMIUM" }
// 201
{ "venueId": "…", "seatsCreated": 30, "totalSeats": 30,
  "createdSeatLabels": ["A1","A2","…"] }

// POST /api/admin/events
{ "venueId": "…", "title": "Coldplay Live", "category": "Concert",
  "startTime": "2026-10-01T19:00:00", "endTime": "2026-10-01T22:00:00",
  "basePrice": "1500.00", "status": "PUBLISHED" }
// 201
{ "event": { … }, "seatsGenerated": 30 }

// GET /api/admin/bookings?status=CONFIRMED&limit=50 -> 200
{ "bookings": [ { …, "user": { "name": "Alice Kapoor", "email": "alice@example.com" } } ],
  "total": 812, "limit": 50, "offset": 0 }
```

`priceMultiplier` may be omitted on a seat layout; tier defaults apply (REGULAR 1.00,
PREMIUM 1.50, VIP 2.50). `DELETE /admin/events/{id}` is a soft delete because
`bookings.event_id` references the row — a hard delete would either fail on the
foreign key or destroy booking history. The venue of an existing event cannot be
changed: its seat rows are derived from it, so moving it would invalidate every booking.

Admin listings carry a `user` block that user-facing responses omit. `total` counts all
matching rows, not the current page, so a client can render "showing 1–50 of 812".
An out-of-range `limit` is a 400 rather than being clamped: silently returning 200 rows
would read as "there are only 200 bookings".

Two protections apply to every admin route, and the second is not redundant —
authentication proves a token is genuine, it says nothing about authorisation:

1. `authenticate(JWT_AUTH)` rejects a missing or invalid token → `401`.
2. `requireAdmin` rejects a valid non-admin token → `403`.

### 5.5 Errors

Every failure, from any endpoint, uses one envelope:

```jsonc
{ "error": "VALIDATION_ERROR", "message": "email is required", "field": "email" }
```

`field` appears on validation errors; `unavailableSeats` on `SEATS_UNAVAILABLE`; both
are omitted otherwise.

| Status | `error` | Raised when |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Bad or missing input, malformed JSON, bad UUID/date/number |
| 401 | `UNAUTHORIZED` | No token, invalid token, or wrong credentials on login |
| 403 | `FORBIDDEN` | Valid token, but not allowed — non-admin, or another user's booking |
| 404 | `NOT_FOUND` | Missing resource, a non-public event, or an unmatched route |
| 409 | `EMAIL_ALREADY_REGISTERED` | Registering an email that already exists |
| 409 | `SEATS_UNAVAILABLE` | One or more seats are held or sold — carries `unavailableSeats` |
| 409 | `EVENT_NOT_BOOKABLE` | Holding seats for a `DRAFT` or `CANCELLED` event |
| 409 | `EVENT_ALREADY_STARTED` | Holding, or a customer cancelling, after `start_time` |
| 409 | `HOLD_EXPIRED` | Confirming or cancelling a hold whose 24 hours lapsed |
| 409 | `BOOKING_CANCELLED` | Confirming a cancelled booking |
| 409 | `BOOKING_HAS_NO_SEATS` | Confirming a booking whose seat claims were released |
| 409 | `VENUE_HAS_NO_SEATS` | Creating an event at a venue with no seat layout |
| 409 | `SEATS_ALREADY_EXIST` | A layout request collides with existing seat labels |
| 500 | `INTERNAL_ERROR` | Anything unanticipated — logged with a stack trace, never leaked |

---

## 6. Configuration

All settings live in `Backend/src/main/resources/application.conf` and can be
overridden by environment variable. See `Backend/.env.example`.

| Variable | Default | Notes |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | — | Required. No defaults, so a missing value is caught at boot |
| `DB_POOL_SIZE` | 5 | Supabase caps concurrent connections; a non-blocking server needs few |
| `JWT_SECRET` | — | **Required**, 32+ chars. No fallback exists — a built-in default would let anyone forge an admin token |
| `JWT_EXPIRY_HOURS` | 24 | |
| `HOLD_TTL_HOURS` | 24 | How long seats stay held |
| `HOLD_SWEEP_INTERVAL_MINUTES` | 5 | Must be positive; disabling would leak seats out of sale |
| `MAX_SEATS_PER_BOOKING` | 10 | |
| `ADMIN_BOOTSTRAP_EMAIL` / `_PASSWORD` / `_NAME` | unset | Ensures an ADMIN exists; blank disables |
| `CORS_ALLOWED_HOSTS` | `*` | Any origin by default, with a startup warning. Set explicit origins before deploying |
| `PORT` | 8080 | |

---

## 7. Testing and verification

### 7.1 Unit tests

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
cd Backend
.\gradlew.bat test        # 284 tests
```

No database or server required: repositories have in-memory fakes. The fakes
deliberately reproduce the constraints that matter — `FakeBookingRepository.create`
refuses a seat that already has an active claim, exactly as `uq_booking_seats_active`
would, which is what lets the cancel-then-rebook path be tested honestly.

The suite is two layers. 265 service tests cover the rules with no HTTP involved.
`ApiEndpointTest` adds 19 that drive every route over real HTTP through Ktor's
`testApplication`, which is the only way to prove the wiring: that routes sit at the
spec's paths, that the 401/403 guards are actually attached, that exceptions become the
documented status codes, and that the JSON on the wire carries the field names the
frontend reads.

Time is injected through `TimeProvider`, so hold expiry, the cancellation window and
the sweeper are asserted at exact boundaries instead of by sleeping.

There is no bundler or compiler on the frontend, so a mistyped import name or element
id would only surface when a page is opened. `tools/check-frontend-refs.ps1` catches
that class of error statically — every named import resolves to a real export, every
id an entry script looks up exists in the page that loads it, and every page provides
the `#site-header` / `#site-footer` the shared shell fills:

```powershell
powershell -ExecutionPolicy Bypass -File tools\check-frontend-refs.ps1
```

It exits non-zero on a problem and reports links to pages that do not exist yet as
notes rather than failures, since the remaining pages are still being built.

`tools/check-api-contract.ps1` covers the other seam with no shared schema — the
frontend and the Kotlin DTOs. Every request DTO field is nullable with a default, so a
misspelled payload key does not fail loudly: it arrives as null and surfaces as a
confusing "x is required" 400. The script checks that every payload key is a field on
some DTO and that every query parameter the frontend sends is read by some controller:

```powershell
powershell -ExecutionPolicy Bypass -File tools\check-api-contract.ps1
```

It cannot tell which DTO a given call site returns, so that pairing still needs review
by hand — the trap being that `EventSummaryResponse` carries flat `venueName`/`city`
while `EventDetailResponse` nests them under `venue`.

### 7.2 Manual checks that unit tests cannot cover

Both scripts live in `Backend/scripts/`, need a **running server** and an **ADMIN
account** (they create venues and events), and write real rows — point them at a
development project. Everything they create is named `Smoke …` or `Race …` so it is
easy to spot.

Windows blocks unsigned scripts by default, so run them with an explicit policy:

```powershell
$env:ADMIN_BOOTSTRAP_EMAIL    = "admin@example.com"
$env:ADMIN_BOOTSTRAP_PASSWORD = "<the password the server booted with>"

powershell -ExecutionPolicy Bypass -File Backend\scripts\smoke.ps1
powershell -ExecutionPolicy Bypass -File Backend\scripts\concurrency-check.ps1 -Rounds 5
```

Both print a PASS/FAIL line per check and exit non-zero if anything failed, so they
work as a regression gate and not just as a demo.

**End-to-end** — `smoke.ps1` walks the whole flow: health → register → duplicate email
rejected → login → `/auth/me` → 401/403 boundaries on admin routes → venue → seat
layout → event → seat map (checking tier pricing) → hold → confirm → history →
ownership refusals → cancel → **re-hold the freed seat** → admin listing and
pagination → error envelope. The re-hold is the concrete proof that the partial unique
index made cancel-then-rebook work.

**Concurrency** — row locking is a PostgreSQL guarantee, not application logic, so it
needs two real clients. `concurrency-check.ps1` starts two background jobs that wait
for a common wall-clock instant before firing a hold at the *same* seat, then asserts
exactly one `201` and one `409 SEATS_UNAVAILABLE`, and that the seat ended up `LOCKED`
once. It reports how many milliseconds apart the two requests were dispatched, because
a sequential pair would pass trivially and prove nothing. Two `201`s would mean the
same seat was sold twice — the failure this whole design exists to prevent.

**Sweeper** — `HOLD_TTL_HOURS` is a positive whole number of hours, so the quickest
demonstration is to set `hold_expires_at` to a past instant in the SQL editor, run with
`HOLD_SWEEP_INTERVAL_MINUTES=1`, and watch the log line plus the seat returning to
`AVAILABLE`.

---

## 8. Known gaps and what I would do differently

- **Seat availability is fetched, not pushed.** The seat map is loaded on page open and
  re-fetched on a `409`. WebSockets would show another user taking a seat live; polling
  or SSE would be a cheaper middle ground.
- **No rate limiting on login.** BCrypt's cost factor makes online guessing slow, but a
  per-IP limit belongs in front of `/auth/login` before this is public.
- **No refunds or payment.** Cancelling frees the seat and records the status; money is
  out of scope.
- **`total_capacity` on a venue is advisory.** The real count comes from the `seats`
  rows, and the API returns both so a mismatch is visible rather than hidden.
- **`GET /events` is not paginated.** Admin booking listings are, but the public event
  listing returns every match. Fine at demo scale and it matches the shape in plan §6.2;
  at real volume it needs the same `limit`/`offset` treatment.
- **Request bodies are unbounded.** Field-level caps exist (row counts, seats per
  booking, text lengths), but a body size limit belongs at the reverse proxy.
- **Plain HTML/CSS/JS frontend** — a deliberate trade: no build tooling and a stack
  that is simple to reason about, at the cost of manual DOM updates.
