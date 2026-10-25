# Tasks

## 1. Project scaffolding

- [x] 1.1 Create the Gradle (Kotlin DSL) project with Java 25 toolchain, Spring Boot 4 (webmvc, validation, jdbc, actuator), Flyway, PostgreSQL driver, springdoc-openapi, Spring Modulith, Testcontainers and AssertJ; verify `./gradlew build` succeeds on an empty application
- [x] 1.2 Add `docker-compose.yml` with PostgreSQL 17 and the application (multi-stage Dockerfile); verify `docker compose up` starts both and `GET /actuator/health` returns `UP`
- [x] 1.3 Configure `local` and `test` profiles, virtual threads, structured JSON logging and the shop `ZoneId` bean; verify the app logs JSON on startup
- [ ] 1.4 Add a GitHub Actions workflow running `./gradlew check` on pull requests and pushes to `main`; verify it passes on the scaffold
- [x] 1.5 Add a Spring Modulith verification test for the `catalog`, `booking` and `shared` modules; verify it passes and fails when `booking` imports a `catalog` internal class

## 2. Shared kernel (time and errors)

- [x] 2.1 Write `TimeRange` unit tests first (half-open overlap, back-to-back, containment, 15-minute grid alignment), then implement the value object; verify the tests pass
- [x] 2.2 Implement the RFC 9457 Problem Details handler with the `validation`, `not-found`, `slot-unavailable`, `booking-expired` and `idempotency-conflict` types; verify with a `MockMvc` test per type
- [x] 2.3 Add the correlation-id filter (`X-Request-Id`, generated when absent, put in MDC and echoed in the response); verify with a web test

## 3. Shop catalog

- [x] 3.1 Write Flyway `V1__catalog.sql` (barber, service, barber_service, working_interval) and the `local`-only demo seed (3 barbers, 4 services, weekly hours including a split shift and a day off); verify migrations run in a Testcontainers test and seed data is absent in the `test` profile
- [x] 3.2 Write unit tests first for "working intervals of a barber on a local date", then implement them; verify day-off and split-shift cases pass
- [x] 3.3 Implement `GET /api/v1/services` and `GET /api/v1/barbers` (active only, with service ids); verify with API tests covering the inactive barber and inactive service scenarios
- [x] 3.4 Expose the catalog's public API package used by `booking` (barber offers service, working intervals, service duration) and return `not-found` for unknown or inactive ids; verify the "Unknown service" scenario and the Modulith test

## 4. Booking domain (test first)

- [x] 4.1 Write unit tests first for the `Booking` state machine (hold, confirm before/after expiration, cancel from HELD and CONFIRMED, idempotent re-confirm and re-cancel, illegal transitions) using a fixed `Clock`; then implement the aggregate and `BookingStatus`; verify all tests pass
- [x] 4.2 Write unit tests first for hold validation (off-grid start, outside working hours, outside the 30-day window, barber does not perform the service, invalid customer data), then implement the validation; verify each spec scenario has a test
- [x] 4.3 Ensure customer data is excluded from `toString()` of every type that holds it; verify with a unit test

## 5. Booking persistence and invariants

- [x] 5.1 Write Flyway `V2__booking.sql` with `btree_gist`, the `booking` table, the `booking_no_overlap` exclusion constraint and supporting indexes; verify with a Testcontainers test that inserting two overlapping active rows fails with `23P01` and back-to-back rows succeed
- [x] 5.2 Implement the hold SQL path: expire-on-write for overlapping expired holds, then insert with `expires_at = now() + interval '10 minutes'`; verify the "Slot reusable at expiration" scenario with an integration test that ages a hold in the database
- [x] 5.3 Implement confirm and cancel as conditional `UPDATE`s, reloading on zero rows to return the right error; verify "Confirm in time", "Confirm too late" and "Cancel a confirmed booking" with integration tests
- [x] 5.4 Implement the sweeper (every 30 s, idempotent `UPDATE`); verify a test where an expired hold reads `EXPIRED` after the sweeper runs, and that two concurrent sweeper runs leave the same result
- [x] 5.5 Translate `23P01` into `slot-unavailable` (409) in the hold use case; verify the overlapping, different-durations and other-barber scenarios end to end

## 6. Idempotency

- [x] 6.1 Write Flyway `V3__idempotency.sql` and the canonical request hashing (SHA-256 of a normalized body); verify hashing is stable across field order with a unit test
- [x] 6.2 Store the key and the booking in the same transaction; on a unique violation, return the stored booking when the hash matches or `idempotency-conflict` when it differs; verify "Client retries after a timeout" and "Key reused with different content"
- [x] 6.3 Verify that two concurrent requests with the same key produce one booking and two identical responses (integration test with 2 threads)
- [x] 6.4 Add the purge job for keys older than 48 hours and reject requests without `Idempotency-Key`; verify with tests for both

## 7. Booking and availability API

- [x] 7.1 Implement `POST /api/v1/bookings`, `GET /api/v1/bookings/{id}`, `POST /api/v1/bookings/{id}/confirm` and `POST /api/v1/bookings/{id}/cancel` with random UUID ids and timestamps in the shop offset; verify the "Successful hold" and "Read after hold" scenarios with API tests
- [x] 7.2 Write unit tests first for the availability calculation (fit in interval, overlap with bookings, expired holds ignored, past times excluded), then implement it; verify every `slot-availability` scenario has a test
- [x] 7.3 Implement `GET /api/v1/availability?serviceId=&date=&barberId=` for one barber and any barber, plus the 30-day window and offset formatting; verify "Two barbers, one busy", "Too far ahead" and "Offset in the response" with API tests
- [x] 7.4 Verify customer data never appears in logs: capture output during a rejected hold and assert that no name, email or phone is present
- [x] 7.5 Publish the OpenAPI document at `/v3/api-docs` and Swagger UI in the `local` profile; verify every endpoint and error type is documented

## 8. End-to-end guarantees

- [x] 8.1 Write the race test: start two application instances on random ports against one Testcontainers PostgreSQL, fire 20 concurrent holds for the same barber and start time from virtual threads behind a `CountDownLatch`; verify exactly one `201`, nineteen `409`, and exactly one active row in the database
- [x] 8.2 Write the "Slot taken between query and booking" test (two clients see the slot as free, both hold, one wins); verify it passes
- [ ] 8.3 Run `./gradlew check` locally and in CI; verify all tests pass and the Modulith test is green

## 9. Documentation

- [x] 9.1 Write the README in English: the problem, the architecture (module and state diagrams), how double-booking is prevented with the alternatives considered, how to run locally, how to run the race test, and the roadmap; verify every command in it runs as written on a clean clone
- [x] 9.2 Add `docs/adr/0001-exclusion-constraint.md` and `docs/adr/0002-idempotency-in-database.md` summarizing decisions D4 and D7; verify the README links to them

## Workflow follow-up

- After review, archive the change with `/opsx:archive` so the three capabilities become the project's main specs.
- Start `add-deposit-payment` as the next change.
