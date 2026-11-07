# Architecture

## Style
Modular monolith: ONE deployable Spring Boot app (single Maven project), several strictly separated modules, each a package under com.hospitalqueue.
Split a module into its own service later only when it needs separate scaling.

## Modules and what each owns
| Module   | Responsibility                                   | Owns tables                          |
|----------|--------------------------------------------------|--------------------------------------|
| patient  | patient profile, slot search, token booking, live status | patient, token, slot_hold (log) |
| doctor   | doctor schedule, queue control, prescriptions    | doctor, schedule, consultation, prescription, prescription_item |
| pharmacy | pharmacy directory, fulfilment queue             | pharmacy, pharmacy_order, pharmacy_order_item |
| billing  | convenience fee log, subscriptions, feature flags| fee_ledger, subscription, plan       |
| admin    | hospital onboarding, analytics                   | hospital, hospital_user              |
| common   | security, errors, audit log                      | audit_log                            |

## Redis usage
- queue:{doctorId}:{date}  ordered list of waiting token ids
- current:{doctorId}:{date}  token now being served
- hold:{slotId}  slot lock with 15 minute TTL during checkout

## Token booking flow
1. Patient searches doctors by hospital and specialty.
2. Patient picks a slot -> Redis hold (15 min TTL).
3. Patient pays convenience fee (UPI via payment gateway).
4. Payment success -> token saved in PostgreSQL, added to Redis queue, hold released.
5. Payment fails or timer expires -> hold disappears automatically.
6. Doctor presses "Next" -> current token updates -> patients get live update (WebSocket/SSE).

## Prescription and pharmacy flow
1. Doctor completes consultation and writes a prescription (medicines, dose, duration, therapy advice, lab tests).
2. Doctor registration number is printed on the prescription.
3. Patient sees the prescription in the app.
4. Patient chooses a pharmacy (hospital in-house pharmacy is the default option). The prescription is shared ONLY with that pharmacy.
5. Pharmacy order status: SENT -> ACCEPTED (or PARTIAL / REJECTED) -> PREPARING -> READY -> COLLECTED.
6. Pharmacy staff sets availability per medicine when accepting. Real-time stock sync is a later feature.
7. Patient tracks the pharmacy queue and gets a "ready" alert.
8. If rejected or partial, patient can send the prescription to another pharmacy.

## Security
- JWT login, roles enforced on every endpoint.
- TLS everywhere, database not publicly reachable, encryption at rest.
- Sensitive columns (prescription text) encrypted at field level in a later step.
- Audit log for doctor and pharmacy access to patient data.

## Revenue hooks (design for them now, build later)
- fee_ledger row for every booked token (convenience fee).
- plan and subscription tables; feature flags checked per hospital.
- pharmacy: flat monthly subscription (not per-prescription commission).

## Deferred (not in MVP)
Kafka, Kubernetes, separate microservices, real-time pharmacy stock sync, teleconsultation, lab booking, ABDM integration.
