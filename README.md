# Nail Booking

Online appointment booking for a nail salon. Customers choose a service and a time slot, and the system prevents double booking.

## Status

Work in progress.

- [x] PostgreSQL schema with Flyway migrations
- [x] Public API: list services
- [ ] Available time slots API
- [ ] Booking API with double-booking protection
- [ ] Admin page for the technician
- [ ] LINE notification

## Tech stack

Java 17, Spring Boot, Spring Security, Spring Data JPA, PostgreSQL 16, Flyway, Docker Compose, Maven

## Run locally

1. Start the database: `docker compose up -d`
2. Start the app: `.\mvnw spring-boot:run` (Windows) or `./mvnw spring-boot:run` (Mac/Linux)
3. Open `http://localhost:8080/api/public/services`

## Design notes

Double booking is prevented inside the database with a PostgreSQL exclusion constraint, so two customers who book the same time at the same moment cannot both succeed.