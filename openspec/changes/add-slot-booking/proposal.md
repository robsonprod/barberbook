# Proposal

## Why

A barbershop that takes bookings online fails in one way that customers never forgive: two people show up for the same barber at the same time. Preventing that is a real distributed-systems problem: concurrent requests, retries, and abandoned bookings that must free the slot again. It is the foundation every later BarberBook feature (deposit payment, admin panel, service extraction) builds on, so it comes first.

## What Changes

- New back-end application (Java 25, Spring Boot 4, PostgreSQL) structured as a modular monolith with `catalog` and `booking` modules.
- Read-only catalog of the shop's barbers, services (each with a duration and price) and weekly working hours, seeded through a database migration.
- Availability query: free start times for a service on a given day, for one barber or for any barber.
- Booking lifecycle: a customer **holds** a slot for a limited time, then the hold is **confirmed**, **cancelled**, or **expires**. Confirmation is a plain API call in this change; the next change (`add-deposit-payment`) will confirm the booking when the deposit is paid.
- Double-booking is impossible at the database level, even under concurrent requests from many clients.
- Creating a hold is idempotent: a retried request with the same idempotency key returns the original booking instead of creating a second one.
- Local environment with `docker compose up` (PostgreSQL and the application), OpenAPI documentation, and a CI pipeline running unit and integration tests.

## Capabilities

### New Capabilities
- `shop-catalog`: the barbers, the services they offer (duration and price) and their weekly working hours, as exposed to customers.
- `slot-availability`: which start times are free for a given service, day and barber, given working hours and existing bookings.
- `booking-lifecycle`: holding, confirming, cancelling and expiring bookings, including the no-double-booking and idempotency guarantees.

### Modified Capabilities
- None (greenfield project).

## Out of Scope

| Not in this change | Covered by |
|---|---|
| Deposit payment, Mercado Pago webhook, saga between booking and payment | `add-deposit-payment` |
| Staff login, roles, protecting the admin API | `add-admin-auth` |
| Editing barbers, services and working hours; admin screens, charts, Excel export | `add-admin-panel` |
| Payments as a separate service over gRPC | `extract-payment-service` |
| Deploying to AWS | `add-aws-deployment` |
| Customer accounts and login | Not planned: customers identify themselves by name, email and phone per booking |
| Time off, holidays, breaks inside a working day | Later change, not yet on the roadmap |
| Notifications (email/WhatsApp reminders) | Later change, not yet on the roadmap |

## Impact

- **Code**: new repository content: Gradle build, Spring Boot application, Flyway migrations, tests, `docker-compose.yml`, GitHub Actions workflow.
- **APIs**: new public REST API under `/api/v1` (catalog, availability, bookings). No authentication yet; it is not deployed publicly in this change.
- **Dependencies**: PostgreSQL 17 with the `btree_gist` extension; Testcontainers for integration tests.
- **Security impact**: the API stores customer name, email and phone. They are never written to logs (only the booking id), and input is validated at the API boundary. Abuse protection (rate limiting) arrives with the AWS deployment; until then the API runs only locally.
- **Cost impact**: none, since everything runs locally and in GitHub Actions' free tier.
