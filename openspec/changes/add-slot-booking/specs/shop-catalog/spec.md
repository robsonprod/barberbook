# Spec Delta

## Purpose

Expose the shop's barbers, the services they offer and their weekly working hours, so customers can choose what to book and the scheduling rules have a single source of truth.

## ADDED Requirements

### Requirement: List services
The system SHALL list the shop's active services, each with an id, a name, a duration in minutes and a price with its currency. Durations SHALL be multiples of 15 minutes.

#### Scenario: Customer lists services
- **WHEN** a client requests the list of services
- **THEN** the response contains every active service with id, name, duration in minutes, price and currency
- **AND** inactive services are not included

### Requirement: List barbers and what they offer
The system SHALL list the shop's active barbers, each with an id, a display name and the ids of the services that barber performs.

#### Scenario: Customer lists barbers
- **WHEN** a client requests the list of barbers
- **THEN** the response contains every active barber with the services they perform

#### Scenario: Inactive barber
- **WHEN** a barber is marked inactive
- **THEN** the barber no longer appears in the list and cannot be booked

### Requirement: Weekly working hours
Each barber SHALL have weekly working hours defined per weekday as one or more intervals in the shop's local time zone (America/Fortaleza). A weekday without intervals means the barber does not work that day.

#### Scenario: Day off
- **WHEN** a barber has no working interval on Sunday
- **THEN** no slot for that barber is ever offered or accepted on a Sunday

#### Scenario: Split shift
- **WHEN** a barber works 09:00–12:00 and 13:00–18:00 on Monday
- **THEN** no booking for that barber can overlap 12:00–13:00 on a Monday

### Requirement: Unknown catalog references
Requests that reference a service or barber id that does not exist or is inactive SHALL be rejected with a not-found error that names the unknown reference.

#### Scenario: Unknown service
- **WHEN** a client asks for availability of a service id that does not exist
- **THEN** the response is a not-found error identifying the service id
