# Spec Delta

## Purpose

Tell customers which start times they can book for a service on a given day, with a specific barber or with any barber, based on working hours and existing bookings.

## ADDED Requirements

### Requirement: Free start times for a barber
Given a service, a local date and a barber, the system SHALL return the start times on a 15-minute grid where the whole service duration fits inside one of the barber's working intervals and does not overlap any active booking of that barber.

#### Scenario: Slot fits the working interval
- **WHEN** a barber works 09:00–12:00 and the service lasts 45 minutes
- **THEN** 11:15 is offered and 11:30 is not, because 11:30 + 45 minutes ends after 12:00

#### Scenario: Existing booking blocks overlapping starts
- **WHEN** the barber has a confirmed 10:00–10:30 booking and the requested service lasts 30 minutes
- **THEN** 09:45, 10:00 and 10:15 are not offered
- **AND** 09:30 and 10:30 are offered, because back-to-back bookings do not overlap

#### Scenario: Barber does not perform the service
- **WHEN** availability is requested for a barber who does not perform the service
- **THEN** the request is rejected with a validation error

### Requirement: Free start times for any barber
When no barber is specified, the system SHALL return every start time offered by at least one barber who performs the service, and for each start time the ids of the barbers free at that time.

#### Scenario: Two barbers, one busy
- **WHEN** barber A is booked at 10:00 and barber B is free at 10:00
- **THEN** 10:00 is returned with only barber B listed

### Requirement: Active bookings
Only bookings that are confirmed, or held and not yet expired, SHALL block availability. Cancelled and expired bookings SHALL NOT block any start time.

#### Scenario: Expired hold frees the slot
- **WHEN** a hold for 14:00 passes its expiration time
- **THEN** 14:00 is offered again, even before the hold is marked as expired

#### Scenario: Cancelled booking frees the slot
- **WHEN** a confirmed 15:00 booking is cancelled
- **THEN** 15:00 is offered again

### Requirement: Booking window
The system SHALL only offer start times later than the current time and no more than 30 days ahead. Requests for a date outside this window SHALL be rejected with a validation error.

#### Scenario: Same day
- **WHEN** availability for today is requested at 10:07 local time
- **THEN** no start time at or before 10:07 is returned

#### Scenario: Too far ahead
- **WHEN** availability is requested for a date 31 days ahead
- **THEN** the request is rejected with a validation error

### Requirement: Time representation
Requests SHALL take the date in the shop's local calendar (ISO-8601 date). Responses SHALL express start and end times as ISO-8601 timestamps with the shop's UTC offset.

#### Scenario: Offset in the response
- **WHEN** availability for 2026-11-10 is returned
- **THEN** a 09:00 start is expressed as `2026-11-10T09:00:00-03:00`

### Requirement: Availability is advisory
Availability SHALL reflect the state at the time of the request and SHALL NOT reserve anything. A start time shown as free can be taken by someone else before the customer books it.

#### Scenario: Slot taken between query and booking
- **WHEN** two customers see 16:00 as free and both try to book it
- **THEN** only one booking succeeds and the other customer receives a slot-unavailable error
