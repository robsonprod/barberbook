# Spec Delta

## Purpose

Let customers reserve a barber's time and move that reservation through its lifecycle (held, confirmed, cancelled, expired) while guaranteeing that a barber is never booked twice for overlapping times.

## ADDED Requirements

### Requirement: Hold a slot
A customer SHALL be able to hold a start time by sending a barber id, a service id, a start time and their name, email and phone. A successful hold SHALL return a booking with status `HELD`, its start and end times, and an expiration time 10 minutes after creation.

#### Scenario: Successful hold
- **WHEN** a customer holds barber A for a 30-minute service at 10:00 and the slot is free
- **THEN** the response is created with status `HELD`, start 10:00, end 10:30 and an expiration 10 minutes from now

### Requirement: Hold validation
A hold SHALL be rejected with a validation error, before any reservation, when the start time is not on the 15-minute grid, falls outside the booking window, does not fit the barber's working hours, when the barber does not perform the service, or when customer data is missing or malformed.

#### Scenario: Outside working hours
- **WHEN** a customer holds 11:45 for a 30-minute service and the barber stops working at 12:00
- **THEN** the hold is rejected with a validation error explaining that the slot is outside working hours

#### Scenario: Off-grid start
- **WHEN** a customer holds 10:10
- **THEN** the hold is rejected with a validation error

#### Scenario: Invalid email
- **WHEN** the customer email is not a valid address
- **THEN** the hold is rejected with a validation error naming the email field

### Requirement: No double booking
The system SHALL never have two active bookings (confirmed, or held and not expired) for the same barber whose time ranges overlap. A hold that would overlap SHALL be rejected with a slot-unavailable conflict error.

#### Scenario: Overlapping hold
- **WHEN** barber A has a confirmed 10:00–10:30 booking and a customer holds 10:15 for a 30-minute service
- **THEN** the hold is rejected with a slot-unavailable conflict error

#### Scenario: Different durations overlap
- **WHEN** barber A has a held 10:00–11:00 booking and a customer holds 10:30 for a 15-minute service
- **THEN** the hold is rejected with a slot-unavailable conflict error

#### Scenario: Back-to-back bookings
- **WHEN** barber A has a confirmed 10:00–10:30 booking and a customer holds 10:30
- **THEN** the hold succeeds

#### Scenario: Other barber at the same time
- **WHEN** barber A is booked at 10:00 and a customer holds barber B at 10:00
- **THEN** the hold succeeds

### Requirement: Concurrency safety
The no-double-booking guarantee SHALL hold under concurrent requests from many clients, without relying on a single application instance.

#### Scenario: Race for the same slot
- **WHEN** 20 clients send a hold for barber A at 10:00 at the same moment, through two application instances
- **THEN** exactly one hold succeeds
- **AND** the other 19 receive a slot-unavailable conflict error
- **AND** the database contains exactly one active booking for barber A at 10:00

### Requirement: Idempotent hold
A hold request SHALL require an idempotency key. Repeating a request with the same key and the same content SHALL return the original booking without creating another one. Reusing a key with different content SHALL be rejected. Keys SHALL be remembered for at least 24 hours.

#### Scenario: Client retries after a timeout
- **WHEN** a client sends a hold, the response is lost, and the client resends it with the same idempotency key
- **THEN** the second response returns the same booking id
- **AND** only one booking exists

#### Scenario: Key reused with different content
- **WHEN** a client reuses an idempotency key with a different start time
- **THEN** the request is rejected with an idempotency-conflict error

#### Scenario: Missing key
- **WHEN** a hold request has no idempotency key
- **THEN** the request is rejected with a validation error

### Requirement: Confirm a hold
A held booking that has not expired SHALL be confirmable, changing its status to `CONFIRMED`. Confirming an already confirmed booking SHALL succeed without changes. Confirming an expired or cancelled booking SHALL be rejected with a conflict error.

#### Scenario: Confirm in time
- **WHEN** a held booking is confirmed 5 minutes after creation
- **THEN** its status becomes `CONFIRMED` and it no longer has an expiration

#### Scenario: Confirm too late
- **WHEN** a held booking is confirmed 11 minutes after creation
- **THEN** the confirmation is rejected with a booking-expired conflict error

### Requirement: Cancel a booking
A held or confirmed booking SHALL be cancellable, changing its status to `CANCELLED` and freeing its time immediately. Cancelling an already cancelled booking SHALL succeed without changes.

#### Scenario: Cancel a confirmed booking
- **WHEN** a confirmed 15:00 booking is cancelled
- **THEN** its status is `CANCELLED`
- **AND** another customer can hold 15:00 with the same barber

### Requirement: Hold expiration
A hold not confirmed within 10 minutes SHALL stop blocking its time exactly at its expiration time, and SHALL be marked `EXPIRED` within one minute after that.

#### Scenario: Slot reusable at expiration
- **WHEN** a hold for 14:00 expires and, in the same second, another customer holds 14:00
- **THEN** the new hold succeeds even if the old hold is not yet marked `EXPIRED`

#### Scenario: Status updated
- **WHEN** a hold passes its expiration time
- **THEN** within one minute its status reads `EXPIRED`

### Requirement: Read a booking
A booking SHALL be retrievable by its id, returning its status, barber, service, start and end times, and expiration when held. Booking ids SHALL be random and unguessable.

#### Scenario: Read after hold
- **WHEN** a client reads a booking right after holding it
- **THEN** the response shows status `HELD` and the expiration time

### Requirement: Customer data protection
Customer name, email and phone SHALL only be returned in the booking that contains them and SHALL NOT be written to application logs. Logs SHALL reference bookings by id only.

#### Scenario: Logging a conflict
- **WHEN** a hold is rejected because the slot is unavailable
- **THEN** the log entry contains the booking request's barber, start time and a correlation id, and no customer name, email or phone
