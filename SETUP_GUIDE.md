# Setup and Testing Guide

Follow these steps to get everything up and running. If you are on Windows, you can run all commands in PowerShell from the project root folder.

---

## 1. What You Need
* **JDK 21**: Make sure it's installed (check with `java -version`).
* **A Supabase Account**: We use Supabase for our PostgreSQL database.
* **A Static File Server**: You'll need something to serve the HTML pages (like Node.js, Python, or the VS Code Live Server extension).
* *(Note: You do NOT need to install Gradle. We've included `gradlew.bat` for you!)*

---

## 2. Set Up the Database
First, we need to create our tables in Supabase:
1. Create a new project on [Supabase](https://supabase.com).
2. Open the **SQL Editor** in your Supabase dashboard.
3. Open `Backend/db/01_schema.sql` from this codebase, paste it in, and run it. This creates the tables.
4. Next, open `Backend/db/02_deltas.sql`, paste it in, and run it. This adds some important rules for preventing double-booking. Don't skip this!

---

## 3. Run the Backend
We need to tell the backend how to talk to your database. You'll need your Supabase **Database Password** and **JDBC Connection String** (found in your Supabase Project Settings -> Database).

Open a terminal and run this:

```powershell
# Set your environment variables
$env:JAVA_HOME   = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"  # Update with your JDK path
$env:DB_URL      = "jdbc:postgresql://db.<your-project-ref>.supabase.co:5432/postgres?sslmode=require"
$env:DB_USER     = "postgres"
$env:DB_PASSWORD = "<your supabase database password>"
$env:JWT_SECRET  = "a-super-secret-key-at-least-32-characters"

# Optional: Set up an Admin account on boot
$env:ADMIN_BOOTSTRAP_EMAIL    = "admin@example.com"
$env:ADMIN_BOOTSTRAP_PASSWORD = "admin12345"

# Run it!
cd Backend
.\gradlew.bat run
```

If it worked, the terminal will say it connected to the database and is responding at `http://0.0.0.0:8080`.

---

## 4. Run the Frontend
Because the frontend uses plain HTML and JS, you don't need to "build" it. You just need to serve it! 

*(Make sure your backend terminal is still open and running!)*

Open a second terminal window and use whichever tool you prefer:

**Option A (VS Code)**:
Just right-click `Frontend/index.html` and click **Open with Live Server**.

**Option B (Node.js)**:
```powershell
cd Frontend
npx serve . -l 3000
```

**Option C (Python)**:
```powershell
cd Frontend
python -m http.server 3000
```

---

## 5. Play Around With It!
Now you can open `http://localhost:3000` (or whichever port your frontend is on) in your browser.
1. **Register** a new account and try browsing the events.
2. Click on an event to open the live **Seat Map**.
3. Select a few seats, hold them, and then confirm your booking.
4. Try cancelling your booking from the "My Bookings" page and watch the seat become available again!

If you set up the Admin account in Step 3, you can log out, log back in as `admin@example.com`, and click the "Admin" button in the navigation bar to create venues and add new events.

---

## 6. Running Automated Tests

We wrote some great tests to make sure things like double-booking don't happen. 

**Run Unit Tests** (No database required):
```powershell
cd Backend
.\gradlew.bat test
```

**Run End-to-End Tests** (Requires the server to be running):
```powershell
# In a new terminal, run our smoke tests
powershell -ExecutionPolicy Bypass -File Backend\scripts\smoke.ps1

# Run the concurrency check (tests double-booking prevention)
powershell -ExecutionPolicy Bypass -File Backend\scripts\concurrency-check.ps1 -Rounds 5
```

If you run into any trouble, double-check that your Supabase password is correct and that you're using port `5432` in your `DB_URL`! Happy testing!
