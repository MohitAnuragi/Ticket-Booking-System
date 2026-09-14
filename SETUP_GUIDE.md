# Setup and Testing Guide

A step-by-step guide to running this project and checking that it works.
Follow the steps in order. Every command is PowerShell, run from the project root
(`D:\Ticket Booking System`) unless a step says otherwise.

---

## 0. What you need installed

| Tool | Needed for | Check it works |
|---|---|---|
| **JDK 21** (Eclipse Adoptium) | The backend | `& "$env:JAVA_HOME\bin\java" -version` |
| **A static file server** | The frontend | see [Step 5](#5-run-the-frontend) |
| A Supabase account | The database | — |

Gradle does **not** need installing — the project includes `gradlew.bat`.

> **Note:** this machine currently has no Node.js, Python, Docker or local Postgres.
> Step 5 explains the options for serving the frontend.

---

## 1. Credentials you need to change

### 1.1 The short answer

You need **four** values to start, and they all come from your Supabase project's
**database** settings (not the API settings):

| Variable | What it is | Where to get it |
|---|---|---|
| `DB_URL` | JDBC connection string | Supabase → Project Settings → **Database** → Connection string → **JDBC** |
| `DB_USER` | Database user | Usually just `postgres` |
| `DB_PASSWORD` | **Database** password | Set when you created the project. Forgot it? Project Settings → Database → **Reset database password** |
| `JWT_SECRET` | Signs login tokens | You invent it. 32+ random characters. Step 2 generates one for you |

Two more are optional but strongly recommended, because public registration only ever
creates normal users — without these you would have no admin account:

| Variable | What it is |
|---|---|
| `ADMIN_BOOTSTRAP_EMAIL` | Email of the admin account created at startup |
| `ADMIN_BOOTSTRAP_PASSWORD` | Its password (8+ characters, you choose) |

### 1.2 What you do NOT need

You may have seen Supabase keys called **anon key**, **service_role key**, or a
**Project URL** like `https://abcdefgh.supabase.co`. **This project does not use any of
them.** Those are for Supabase's JavaScript client, which talks to Supabase over HTTP.

This project connects **straight to the PostgreSQL database over JDBC**, the same way any
Java or Kotlin app would. So:

- ❌ Do not set `SUPABASE_URL`, `SUPABASE_ANON_KEY` or `SUPABASE_SERVICE_ROLE_KEY` — nothing reads them.
- ✅ Do set `DB_URL`, `DB_USER`, `DB_PASSWORD` as above.

### 1.3 Getting `DB_URL` right

Your JDBC URL should look like this, with your own project reference in place of
`<project-ref>`:

```
jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require
```

Three rules that will save you time:

1. **Use port 5432, not 6543.** Port 6543 is Supabase's transaction-mode pooler. It does
   not support the prepared statements this app relies on, and you will get random
   "prepared statement already exists" errors. If you must use 6543, add
   `&prepareThreshold=0` to the end of the URL.
2. **Keep `?sslmode=require`.** Supabase refuses unencrypted connections.
3. **`DB_PASSWORD` is your *database* password**, not your Supabase account login and not
   an API key.

### 1.4 Frontend settings (only if you change ports)

| What | File | Default |
|---|---|---|
| Backend address the pages call | `Frontend/js/api.js`, first constant | `http://localhost:8080/api` |
| Which web origins may call the API | `CORS_ALLOWED_HOSTS` env var | any origin (dev only) |

If your backend runs on `localhost:8080`, you do not need to change `api.js` at all.

---

## 2. Set up the database

Do this once.

1. Create a project at [supabase.com](https://supabase.com).
2. Open **SQL Editor** in the Supabase dashboard.
3. Open `Backend\db\01_schema.sql` from this project, paste the whole file in, and run it.
   This creates the seven tables.
4. Open `Backend\db\02_deltas.sql`, paste it in, and run it. This adds required
   corrections — seat lock ownership, hold expiry, and the index that prevents
   double-booking. **Do not skip this file.** The server refuses to start without it.
5. The last query prints a list. You should see 7 rows: `booking_seats`, `bookings`,
   `event_seats`, `events`, `seats`, `users`, `venues`.

Running `02_deltas.sql` twice is harmless, so re-run it if you are unsure.

---

## 3. Start the backend

Open PowerShell in the project root and paste this, replacing the three placeholder
values:

```powershell
$env:JAVA_HOME   = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$env:DB_URL      = "jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require"
$env:DB_USER     = "postgres"
$env:DB_PASSWORD = "<your supabase database password>"

# Generates a random signing key for you. Keep this terminal open — it lives here only.
$env:JWT_SECRET  = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))

# Creates an admin account on startup. Choose your own values.
$env:ADMIN_BOOTSTRAP_EMAIL    = "admin@example.com"
$env:ADMIN_BOOTSTRAP_PASSWORD = "admin12345"

cd Backend
.\gradlew.bat run
```

**These variables only exist in this terminal.** If you close it, set them again. To keep
them permanently:

```powershell
[Environment]::SetEnvironmentVariable("DB_URL", "jdbc:postgresql://...", "User")
```

### What a good startup looks like

The log should include, in this order:

```
Connected to database: jdbc:postgresql://db.***.supabase.co:5432/... (pool size 5)
Schema verified. Row counts: bookings=0, booking_seats=0, ...
Created bootstrap ADMIN account admin@example.com
Hold sweeper started: every 5 minute(s), up to 200 hold(s) per pass
Responding at http://0.0.0.0:8080
```

On later restarts the third line reads `Bootstrap admin already present (…)` instead —
the account is only created once.

The server is designed to **fail immediately** with a clear message rather than start in
a broken state. If it stops, read the message — it names the fix.

### Check it is alive

In a **second** PowerShell window:

```powershell
curl http://localhost:8080/api/health
```

Expected (the API formats its JSON, so it comes back indented):

```json
{
    "status": "UP",
    "database": "connected"
}
```

If `database` says anything else, the API cannot reach Supabase. Go back to §1.3.

---

## 4. Test the backend

### 4.1 Automated tests (no database or server needed)

These run entirely in memory, so you can run them any time — even before setting up
Supabase.

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
cd Backend
.\gradlew.bat test
```

Expected: `BUILD SUCCESSFUL`, 284 tests, 0 failures.
An HTML report lands at `Backend\build\reports\tests\test\index.html`.

### 4.2 End-to-end script (needs the server running)

This walks the whole booking flow against the live API and prints PASS or FAIL for each
of about 50 checks.

**It creates real rows in your database.** Everything it makes is named `Smoke …` so you
can spot it. Use a development project, not production.

```powershell
# In a second terminal, from the project root.
# The admin values must match what the server started with.
$env:ADMIN_BOOTSTRAP_EMAIL    = "admin@example.com"
$env:ADMIN_BOOTSTRAP_PASSWORD = "admin12345"

powershell -ExecutionPolicy Bypass -File Backend\scripts\smoke.ps1
```

Expected last line: `Smoke test: NN checks passed.`

`-ExecutionPolicy Bypass` is needed because Windows blocks unsigned scripts by default.

### 4.3 Double-booking check (needs the server running)

This is the important one. It fires two requests at the **same seat at the same moment**
and proves only one wins.

```powershell
powershell -ExecutionPolicy Bypass -File Backend\scripts\concurrency-check.ps1 -Rounds 5
```

Expected, for every round:

```
client A -> HTTP 201
client B -> HTTP 409
dispatched 3.2 ms apart
  PASS  exactly one client got 201
  PASS  the other got 409
```

Two `201`s would mean the same seat was sold twice. That is the failure the whole design
exists to prevent.

### 4.4 Static checks (no server needed)

The frontend has no compiler, so these catch typos that would otherwise only appear when
a page is opened:

```powershell
powershell -ExecutionPolicy Bypass -File tools\check-frontend-refs.ps1
powershell -ExecutionPolicy Bypass -File tools\check-api-contract.ps1
```

Both should end with `passed`.

---

## 5. Run the frontend

There is no build step. The files just need to be **served over `http://`**.

> **Important:** do not double-click `index.html`. Opening it as `file://` will show a
> blank page, because browsers block JavaScript modules loaded from files.

Pick whichever option you have:

**Option A — VS Code (easiest if you use it)**
Install the *Live Server* extension, right-click `Frontend\index.html` → **Open with Live
Server**. It usually serves on `http://127.0.0.1:5500`.

**Option B — Node.js** (install from [nodejs.org](https://nodejs.org) first)

```powershell
cd Frontend
npx serve . -l 3000
```

**Option C — Python** (install from [python.org](https://python.org) first)

```powershell
cd Frontend
python -m http.server 3000
```

### Then tell the backend to trust that origin

The backend allows any origin by default, which is fine for local work but logs a
warning. To be strict, restart the backend with:

```powershell
$env:CORS_ALLOWED_HOSTS = "http://localhost:3000"   # or http://127.0.0.1:5500 for Live Server
```

The address must match your browser's address bar exactly, including the port.

---

## 6. Click through it yourself

With the backend running and the frontend served, open the site and do this in order.

**As a customer**

1. **Register** — click Register, create an account. You are signed in automatically.
2. **Browse** — the home page lists events. Try the search box, the city and category
   dropdowns, and a date.
3. **Pick seats** — click an event. Click some available seats. The total updates as you
   go. Try selecting 11 seats: it should stop you at 10.
4. **Hold** — click "Hold seats". You land on the booking page, showing a reference like
   `TB-8F2K91`, a 24-hour countdown, and Confirm / Release buttons.
5. **Confirm** — click "Confirm booking". Status becomes CONFIRMED and the countdown
   disappears.
6. **History** — open "My bookings". Your booking is listed with a CONFIRMED badge.
7. **Cancel** — click Cancel and accept the prompt. The status becomes CANCELLED.
8. **Re-book the freed seat** — go back to the event. The seat you cancelled is
   selectable again. This is the proof the schema fix works.

**As an admin** (sign out, then sign in with your `ADMIN_BOOTSTRAP_EMAIL`)

9. **Admin** appears in the top navigation. Open it — you get totals and the next events.
10. **Venues** — create a venue, then use "Add a seat layout" (rows `A, B, C`,
    10 seats per row, PREMIUM). The seat count updates.
11. **Events** — create an event at that venue. The message confirms how many bookable
    seats were generated. Try Edit, then Cancel on an event.
12. **Bookings** — see every customer's booking with their name and email, filter by
    event or status, page through, and cancel one.

**Worth checking too:** sign out and open `my-bookings.html` directly. You should be sent
to the sign-in page and returned to that page after signing in. Signing in as a normal
user and opening `admin/dashboard.html` should say "Administrators only" rather than
letting you in.

---

## 7. If something goes wrong

| What you see | What it means | Fix |
|---|---|---|
| `Cannot start: missing database configuration DB_URL...` | Env vars not set in this terminal | Redo §3 in the same window |
| `Cannot start: JWT_SECRET is not set` | No signing key | Redo the `$env:JWT_SECRET` line |
| `Cannot start: failed to connect to the database` | Wrong password, wrong URL, or project paused | Check §1.3. Confirm the project is awake in the dashboard |
| `Cannot start: the database is missing ... table(s)` | `01_schema.sql` not run | Do §2 step 3 |
| `Cannot start: the unique index 'uq_booking_seats_active' is missing` | `02_deltas.sql` not run | Do §2 step 4 |
| `prepared statement ... already exists` | Using port 6543 | Switch to 5432, or append `&prepareThreshold=0` |
| `health` says `"database":"unreachable"` | Connection dropped or project paused | Restart the backend |
| Frontend page is blank | Opened as `file://` | Serve it over http — §5 |
| Browser console: `CORS policy` | Origin not allowed | Set `CORS_ALLOWED_HOSTS` to your exact origin and restart |
| Browser console: `Could not reach the server` | Backend not running, or wrong address | Start the backend; check `API_BASE_URL` in `Frontend\js\api.js` |
| `running scripts is disabled on this system` | Windows script policy | Add `-ExecutionPolicy Bypass`, as shown |
| Admin nav link missing | Signed in as a normal user | Sign out, sign in with the bootstrap admin |
| Seats look stale | Availability is read on load, not pushed | Click "Refresh availability" |

---

## 8. Quick reference

```powershell
# All automated tests (no server, no database)
cd Backend; .\gradlew.bat test

# Run the backend (needs the env vars from §3)
cd Backend; .\gradlew.bat run

# Live checks (need the server running)
powershell -ExecutionPolicy Bypass -File Backend\scripts\smoke.ps1
powershell -ExecutionPolicy Bypass -File Backend\scripts\concurrency-check.ps1 -Rounds 5

# Static frontend checks (no server)
powershell -ExecutionPolicy Bypass -File tools\check-frontend-refs.ps1
powershell -ExecutionPolicy Bypass -File tools\check-api-contract.ps1

# Is it up?
curl http://localhost:8080/api/health
```

| Setting | Default | Change it when |
|---|---|---|
| `PORT` | 8080 | 8080 is taken |
| `HOLD_TTL_HOURS` | 24 | You want shorter seat holds (whole hours only) |
| `HOLD_SWEEP_INTERVAL_MINUTES` | 5 | Demonstrating expiry — set to 1 |
| `MAX_SEATS_PER_BOOKING` | 10 | Different per-booking limit |
| `DB_POOL_SIZE` | 5 | Rarely; Supabase caps connections |
| `JWT_EXPIRY_HOURS` | 24 | Different login lifetime |

Full documentation is in [`README.md`](README.md). Build history and the current resume
point are in [`PROGRESS.md`](PROGRESS.md). Every environment variable is listed with
comments in `Backend\.env.example`.
