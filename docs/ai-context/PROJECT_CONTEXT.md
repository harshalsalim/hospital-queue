# Project context (paste this FIRST into any AI model)
(Last updated after the project was created and first pushed to GitHub.)

## What we are building
HospitalQueue (working name): a real-time token and queue management system for hospitals in Kerala, with prescription and pharmacy fulfilment.
- Patients book a token for a doctor, pay a small convenience fee, and track the live queue.
- Doctors manage the queue (next, pause, complete) and write prescriptions.
- Patients see their prescription and choose a pharmacy to fulfil it, then track the pharmacy queue.
- Hospital admins manage doctors, subscriptions and see analytics.

## Roles
PATIENT, DOCTOR, PHARMACY_STAFF, HOSPITAL_ADMIN, SYSTEM_ADMIN

## Tech stack (do not change without asking)
- Java 21 (LTS), Spring Boot 4.1.2 (Spring Framework 7, Spring Security 7), Maven, YAML configuration
- PostgreSQL 16 + Flyway migrations (spring-boot-starter-flyway)
- Redis 7 (live queue, slot locks with TTL)
- Spring Security + JWT, role-based access
- JUnit 5 + Testcontainers
- Docker Compose for local development (PostgreSQL and Redis)
- NOT in MVP: Kafka, Kubernetes, microservice split

## Repo layout
ONE Maven project (single module). Each business module is a package.

hospital-queue/
  pom.xml
  docker-compose.yml
  src/main/java/com/hospitalqueue/
    HospitalQueueApplication.java
    patient/    booking, live status, patient profile
    doctor/     doctor queue, consultation, prescriptions
    pharmacy/   pharmacy directory, prescription fulfilment queue
    billing/    convenience fee log, subscriptions, feature flags
    admin/      hospital onboarding, analytics
    common/     shared utilities, error format, security helpers, audit log
  src/main/resources/application.yaml
  src/main/resources/db/migration/   Flyway SQL files
  docs/ai-context/                   these files

## Coding rules
1. Root package: com.hospitalqueue.<module>
2. Layers: controller -> service -> repository. Controllers never touch repositories.
3. Never return JPA entities from APIs. Use DTO classes (Java records).
4. Validate input with jakarta.validation annotations.
5. One JSON error format: { "code": "...", "message": "...", "timestamp": "..." }
6. A module NEVER reads another module's tables. It calls that module's public service interface.
7. IDs are UUID. Timestamps are Instant (UTC).
8. Every schema change is a NEW Flyway file (V1__..., V2__...). Never edit an old one.
9. Never log patient names, phone numbers or prescriptions.
10. No secrets in code. Use environment variables.
11. Every feature gets tests before it is called done.
12. Use Spring Boot 4 / Spring Framework 7 APIs only. If unsure whether a class, annotation or property exists in Spring Boot 4, say so instead of guessing.
13. The JVM default time zone is set to Asia/Kolkata in main(). Do not remove it (PostgreSQL rejects the legacy name Asia/Calcutta).
14. Package-by-module: a package may only use another package through its public service interface.

## Privacy rules
- Development uses FAKE data only. Never paste real patient data into any AI tool.
- MVP stores minimal personal data: name, phone, doctor, slot, prescription.
- Every doctor access to a patient's data is written to an audit table.
- A prescription is visible only to the patient, the prescribing doctor, and the pharmacy the patient chose.

## Rules for the AI assistant
- Do one small task at a time.
- Give complete files, not fragments.
- Do not invent libraries or versions. If unsure, say so.
- If the request is unclear, ask one question before coding.
