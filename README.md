# Ticket Booking System

A ticket booking platform where users can browse events, view a live seat map, hold seats, confirm bookings, and manage history. The system is resilient against double-booking and concurrent access.

##  Tech Stack
- **Backend**: Kotlin 2.4 + Ktor 3.5 (API), Gradle
- **Database**: PostgreSQL (via Supabase) with Exposed ORM + HikariCP
- **Frontend**: Plain HTML, CSS, JS (No build step)

---

##  How to Run Locally

Follow these steps if you have just cloned the project:

### 1. Prerequisites
- **JDK 21** installed (`java -version` to verify)
- A **Supabase** account (or local PostgreSQL) for the database.
- Node.js or Python (to serve the frontend).

### 2. Database Setup
1. Create a project at [Supabase](https://supabase.com).
2. Open the **SQL Editor** in Supabase.
3. Run the following scripts in order:
   - [`Backend/db/01_schema.sql`](file:///d:/Ticket%20Booking%20System/Backend/db/01_schema.sql) (Creates the tables)
   - [`Backend/db/02_deltas.sql`](file:///d:/Ticket%20Booking%20System/Backend/db/02_deltas.sql) (Adds row locking and constraints)

### 3. Backend Setup
Set up the required environment variables in your terminal before running the backend:

```powershell
# In PowerShell (run from the project root)
$env:JAVA_HOME   = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"  # Update with your JDK path
$env:DB_URL      = "jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require"
$env:DB_USER     = "postgres"
$env:DB_PASSWORD = "<your supabase database password>"
$env:JWT_SECRET  = "your-super-secret-key-at-least-32-chars-long"

# Optional: Creates an ADMIN account on first boot
$env:ADMIN_BOOTSTRAP_EMAIL    = "admin@example.com"
$env:ADMIN_BOOTSTRAP_PASSWORD = "admin12345"

# Start the server
cd Backend
.\gradlew.bat run
```
*The backend will start at `http://localhost:8080`. You can check its health at `http://localhost:8080/api/health`.*

### 4. Frontend Setup
The frontend is plain HTML/JS and has no build step. You just need to serve it!

Open a **new terminal**:
```powershell
cd Frontend

# Using Node.js:
npx serve . -l 3000

# OR using Python:
python -m http.server 3000
```
Open `http://localhost:3000` in your browser.

---

##  How to Test

You don't need the server or database running for the unit tests (they use an in-memory fake).
```powershell
cd Backend
.\gradlew.bat test
```
*Expected: BUILD SUCCESSFUL (284 tests passed)*

If you want to run the **End-to-End** and **Concurrency** tests against your live database, make sure the backend is running, then run these scripts in a separate terminal:
```powershell
# E2E Smoke Flow
powershell -ExecutionPolicy Bypass -File Backend\scripts\smoke.ps1

# Double-booking prevention check
powershell -ExecutionPolicy Bypass -File Backend\scripts\concurrency-check.ps1 -Rounds 5
```

---

## Detailed Documentation
- **[SETUP_GUIDE.md](SETUP_GUIDE.md)** - Extremely detailed step-by-step setup and troubleshooting guide.
- **[ARCHITECTURE.md](ARCHITECTURE.md)** - Details on MVC architecture, API endpoints, error handling, and design decisions.
