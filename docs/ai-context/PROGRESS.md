# Progress log (update after every task)

## Decisions
- Modular monolith: ONE Maven project, one package per module (changed from multi-module Maven to keep setup simple; can be converted later).
- Pharmacy is its own module; the patient chooses the pharmacy.
- Java 21 LTS, Spring Boot 4.1.2, PostgreSQL + Flyway, Redis, YAML config.
- Local development first, deploy to GCP (Mumbai region) later.
- IntelliJ must use "Bundled Maven 3" (Settings > Build Tools > Maven), not the Maven wrapper; the wrapper import failed silently.
- JVM time zone forced to Asia/Kolkata (in main() and in the surefire argLine in pom.xml).

## Done
- [x] Project idea and specification written
- [x] Architecture decided
- [x] Spring Boot project created and opens in IntelliJ (Java 21)
- [x] docker-compose.yml with PostgreSQL and Redis; app starts and connects
- [x] Git repository created; private GitHub repo hospital-queue; first commit pushed

## Next (in order)
- [ ] 1. Add docs/ai-context files to the project and commit
- [ ] 2. Copy PROJECT_CONTEXT.md into .continue/rules/ for Continue
- [ ] 3. Create the package skeleton (patient, doctor, pharmacy, billing, admin, common)
- [ ] 4. Database design reviewed, then first Flyway migration
- [ ] 5. Token booking with Redis slot hold + tests
- [ ] 6. Doctor queue (next, pause, complete) + tests
- [ ] 7. Prescriptions
- [ ] 8. Pharmacy directory and fulfilment queue
- [ ] 9. Login and roles
- [ ] 10. Billing hooks (fee ledger, plans)
- [ ] 11. Update Spring Boot to the latest patch version (security advisories shown in IntelliJ)
- [ ] 12. UI

## Notes for the next AI model
Docker Desktop must be running; start databases with `docker compose up -d`.
(Write here anything the next model must know: current task, files touched, open problems.)
