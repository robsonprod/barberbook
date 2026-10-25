# ADR 0001 — Prevent double-booking with a PostgreSQL exclusion constraint

- **Status:** Accepted
- **Date:** 2026-10-24
- **Change:** `add-slot-booking` (design decisions D4–D6)

## Context

A barber must never have two active bookings whose time ranges overlap. Requests arrive concurrently, possibly through several application instances, and bookings have different durations (15 to 60 minutes). A booking is "active" while it is `CONFIRMED`, or `HELD` and not yet expired.

## Decision

Enforce the invariant in PostgreSQL with an exclusion constraint over `(barber_id, tstzrange(starts_at, ends_at, '[)'))`, limited to rows in status `HELD` or `CONFIRMED`. The application performs **no** availability check before inserting; it translates `SQLSTATE 23P01` into `409 slot-unavailable`.

Because a constraint predicate cannot reference `now()`, expiration is handled by:
1. **expire-on-write** — the hold transaction first marks overlapping holds with `expires_at <= now()` as `EXPIRED`;
2. a **sweeper** running every 30 s with an idempotent `UPDATE`.

`expires_at` is computed and compared with the database clock only.

## Alternatives considered

| Option | Outcome |
|---|---|
| Check availability in Java, then insert | Rejected — race condition between check and insert |
| `UNIQUE (barber_id, starts_at)` | Rejected — overlapping ranges with different starts pass |
| Lock the barber row (`SELECT … FOR UPDATE`) | Rejected — correct but relies on every writer remembering the lock; serializes all writes per barber |
| `SERIALIZABLE` transactions | Rejected — correct but surfaces as generic serialization failures needing retries |
| Distributed lock (Redis) | Rejected — new infrastructure; lock expiry can still admit two writers |

## Consequences

- ✅ The guarantee holds for any number of instances and cannot be bypassed by a code path that forgets a check.
- ✅ Back-to-back bookings are legal thanks to half-open ranges `[)`, matching `TimeRange` in the domain.
- ⚠️ PostgreSQL-specific (`btree_gist`). Accepted: the invariant matters more than portability.
- ⚠️ Every hold runs one extra `UPDATE` (expire-on-write) on the same index as the constraint.
- 🧪 Verified by `NoOverlapConstraintTest` (raw SQL), `RaceConditionTest` (20 clients × 2 instances) and its negative control (constraint dropped → double bookings).
