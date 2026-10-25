# ADR 0002 — Idempotency keys stored in PostgreSQL, in the booking transaction

- **Status:** Accepted
- **Date:** 2026-10-24
- **Change:** `add-slot-booking` (design decision D7)

## Context

Clients retry `POST /bookings` after timeouts or network errors. Without protection, a retry of a request that actually succeeded would try to create a second booking — and, worse, the original customer would receive a `409` for their own slot.

## Decision

`POST /bookings` requires an `Idempotency-Key` header. Inside the hold transaction:

1. `INSERT INTO idempotency_key … ON CONFLICT (key) DO NOTHING` — **before** creating the booking;
2. if the key already existed: compare the SHA-256 of the normalized request; same → return the stored booking (`200`, `Idempotent-Replayed: true`); different → `422 idempotency-conflict`;
3. otherwise validate and insert the booking; the key's foreign key to `booking` is `DEFERRABLE INITIALLY DEFERRED` because the key is written first.

Any failure rolls back both rows, so a retry of a failed request is evaluated again. Keys are purged after 48 hours (the contract promises at least 24).

## Why `ON CONFLICT DO NOTHING` and not catching the unique violation

In PostgreSQL an error aborts the whole transaction; catching a unique violation would leave nothing usable to read the stored key with. `ON CONFLICT DO NOTHING` does not error. And when another transaction is inserting the same key at that moment, PostgreSQL makes this one **wait** for it to finish — which is exactly the serialization needed for concurrent duplicates, with no extra locking code.

## Alternatives considered

| Option | Outcome |
|---|---|
| Keys in Redis | Rejected — key and booking could not commit atomically |
| Hash of the raw JSON body | Rejected — field order or phone formatting would change the hash; the hash is computed from normalized fields |
| Insert the booking first, then the key | Rejected — two concurrent duplicates would race on the booking and one would get `409` instead of the original booking |

## Consequences

- ✅ Exactly-once booking creation per key, including concurrent duplicates (`BookingLifecycleTest.concurrentRequestsWithTheSameKeyCreateOneBooking`).
- ✅ No extra infrastructure.
- ⚠️ One extra write per hold and a purge job.
