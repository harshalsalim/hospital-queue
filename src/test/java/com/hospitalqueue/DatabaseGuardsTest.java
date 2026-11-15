package com.hospitalqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A self-check of the database the tests run against (Docker must be running). Several rules of the application are
 * guaranteed by database indexes and constraints that come from migration files. If a migration file is missing from
 * the project, was misnamed, or was not applied, the services still compile and most tests pass, but concurrency
 * guarantees silently disappear. This test fails with a clear message that names the missing piece.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseGuardsTest {

    /** Guards the application relies on, with the migration that creates each one. */
    private static final List<String> REQUIRED_INDEXES = List.of(
            "uq_specialty_name_lower",                  // V2
            "uq_doctor_registration_lower",             // V3
            "uq_hospital_name_address",                 // V4
            "ex_slot_no_overlap",                       // V5
            "uq_fee_ledger_pending_patient_slot",       // V6
            "uq_token_one_in_consultation_per_slot");   // V7

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void everyMigrationFileHasBeenAppliedWithoutGaps() {
        List<Integer> applied = jdbcClient
                .sql("SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL")
                .query(String.class)
                .list()
                .stream()
                .map(Integer::parseInt)
                .sorted()
                .toList();

        int newest = applied.get(applied.size() - 1);
        List<Integer> missing = IntStream.rangeClosed(1, newest)
                .boxed()
                .filter(version -> !applied.contains(version))
                .toList();

        assertThat(missing)
                .as("Migration versions missing from src/main/resources/db/migration (applied: %s)", applied)
                .isEmpty();
    }

    @Test
    void everyIndexAndConstraintTheApplicationRelyOnExists() {
        List<String> present = jdbcClient
                .sql("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
                .query(String.class)
                .list();

        List<String> missing = REQUIRED_INDEXES.stream().filter(name -> !present.contains(name)).toList();

        assertThat(missing)
                .as("Database guards that do not exist; the migration that creates each one is listed in this test")
                .isEmpty();
    }
}
