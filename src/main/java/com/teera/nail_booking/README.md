# Nail Salon Booking

A mobile-first online booking system for a small nail salon with a single technician. Customers pick one or more services, see only the time slots that are actually free, and attach reference photos. The technician reviews the day's appointments (with photos) on a password-protected page and can block off time for breaks or days off.

It is built to remove the back-and-forth of booking over chat: the customer sees real availability, so nobody has to ask "is 2 pm free?".

| Customer booking page | Technician dashboard |
| --- | --- |
| ![Customer booking page](docs/customer-booking.png) | ![Technician dashboard](docs/staff-dashboard.png) |

> Screenshots use sample data. The UI text is in Thai because the salon's customers are Thai.

## Features

**Customer side**
- Select multiple services plus add-ons (nail extensions, nail art). Total duration and price are calculated automatically.
- Mutually exclusive items are enforced (for example, "hand + feet gel" cannot be combined with "hand gel" alone).
- Only slots that fit the whole job before closing time and do not clash with other bookings are selectable.
- Attach up to 5 reference photos. Images are resized and compressed in the browser before upload.
- Mobile-first layout with dark mode support.

**Technician side**
- Day-by-day agenda for the next 14 days with a booking count per day.
- Customer name, tap-to-call phone number, selected services, price, notes, and reference photos (tap to enlarge).
- Cancel a booking, or block time (lunch break, day off) so customers cannot book it.
- Auto-refreshes every 30 seconds.

## Tech stack

| Layer | Technology |
| --- | --- |
| Language / framework | Java 17+, Spring Boot 4.1 (Web, Security, JDBC/JPA, Validation, Actuator) |
| Database | PostgreSQL 16 (Docker Compose) |
| Migrations | Flyway |
| Build | Maven (wrapper included) |
| Frontend | Single-file HTML/CSS/vanilla JavaScript served from `src/main/resources/static` |

## Design decisions

- **Double-booking is prevented by the database, not just the application.** The `booking` table has a PostgreSQL exclusion constraint (`btree_gist`, `tstzrange(...) WITH &&`) so two overlapping active bookings cannot be stored even if two customers submit at the same instant. The API translates the constraint violation into a friendly `409 Conflict`. The application also checks availability first so most conflicts never reach the database.
- **Buffer time is stored in the booking's end time**, so the same constraint enforces cleaning/changeover time between customers.
- **All time logic uses an injected `Clock` bean** set to `Asia/Bangkok`, which keeps time-dependent code deterministic and easy to test.
- **Services and prices live in the database** (`menu_item`), seeded by a Flyway migration, so they can be changed without touching code.
- **Reference photos are stored in PostgreSQL (`BYTEA`)** after client-side compression. This keeps the demo self-contained (no object storage needed). Uploads require a per-booking random token returned at booking time, so only the customer who made the booking can attach photos; viewing photos requires staff login.
- **Schema changes go through Flyway migrations only** (`V1`–`V5`); applied migrations are never edited.

## API overview

Public (no login):

| Method | Path | Description |
| --- | --- | --- |
| GET | `/api/public/menu` | Services, add-ons, prices, durations, exclusions |
| GET | `/api/public/slots?items=a,b&date=YYYY-MM-DD` | Free/busy slots for the selected items on a date |
| POST | `/api/public/bookings` | Create a booking (returns id and a photo-upload token) |
| POST | `/api/public/bookings/{id}/photos?token=...` | Upload one JPEG reference photo (raw body, max 2 MB, 5 per booking) |

Staff (HTTP Basic auth):

| Method | Path | Description |
| --- | --- | --- |
| GET | `/api/staff/bookings?from=YYYY-MM-DD&days=7` | Bookings and blocked time in a date range |
| POST | `/api/staff/bookings/{id}/cancel` | Cancel a booking or remove a block |
| POST | `/api/staff/blocks` | Block time (`date`, `start`, `end`, `note`) |
| GET | `/api/staff/photos/{id}` | Download a reference photo |

## Project structure

```
src/main/java/com/teera/nail_booking
├── availability   legacy availability endpoint
├── booking        booking entity and repository
├── catalog        original service catalog endpoint
├── config         security and clock configuration
├── reservation    menu, slots, booking creation, photo upload
└── staff          staff API and staff user configuration
src/main/resources
├── db/migration   Flyway migrations (V1–V5)
└── static         index.html (customer), staff.html (technician)
```

## Running locally

Prerequisites: JDK 17 or newer and Docker Desktop.

```bash
# 1. Start PostgreSQL
docker compose up -d

# 2. Run the application (Flyway creates the schema on first start)
./mvnw spring-boot:run          # Windows PowerShell: .\mvnw spring-boot:run
```

Then open:

- Customer page: <http://localhost:8080/>
- Technician page: <http://localhost:8080/staff.html> (username `staff`)

The technician password defaults to a development-only value. Set your own before starting the app:

```powershell
$env:STAFF_PASSWORD="choose-a-password"     # PowerShell
# export STAFF_PASSWORD=choose-a-password   # macOS / Linux
```

> The database credentials in `docker-compose.yml` and `application.yml` are for local development only. Move them to environment variables or a secrets manager before any real deployment.

## Known limitations and roadmap

- No automated tests yet. Planned: unit tests for slot calculation and the booking rules, and an integration test for the double-booking constraint using Testcontainers.
- Service durations are estimates and still need to be confirmed with the salon.
- Technician login is a single shared HTTP Basic account.
- Not yet deployed. A production setup needs environment-based secrets, backups, and HTTPS.
- No notifications yet (for example a LINE message to the technician when a booking arrives).
- Photos are stored in the database; object storage would be a better fit at scale.
