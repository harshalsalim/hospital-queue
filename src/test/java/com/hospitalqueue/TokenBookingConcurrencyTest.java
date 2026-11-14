package com.hospitalqueue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.hospitalqueue.admin.dto.CreateHospitalRequest;
import com.hospitalqueue.admin.dto.CreateSpecialtyRequest;
import com.hospitalqueue.admin.dto.HospitalResponse;
import com.hospitalqueue.admin.dto.SpecialtyResponse;
import com.hospitalqueue.admin.service.HospitalService;
import com.hospitalqueue.admin.service.SpecialtyService;
import com.hospitalqueue.common.config.ClockConfig;
import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.dto.CreateDoctorRequest;
import com.hospitalqueue.doctor.dto.CreateSlotRequest;
import com.hospitalqueue.doctor.dto.DoctorResponse;
import com.hospitalqueue.doctor.dto.SlotResponse;
import com.hospitalqueue.doctor.service.DoctorService;
import com.hospitalqueue.doctor.service.SlotService;
import com.hospitalqueue.patient.dto.RegisterPatientRequest;
import com.hospitalqueue.patient.service.PatientService;
import com.hospitalqueue.patient.service.TokenService;
import com.hospitalqueue.patient.service.TokenService.BookingResult;

/**
 * Proves the booking guarantees against a REAL PostgreSQL database (started by Testcontainers, so Docker must
 * be running): many patients competing for few tokens never oversell a session or repeat a token number, and
 * simultaneous identical requests from one patient produce exactly one token.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TokenBookingConcurrencyTest {

    @Autowired
    private HospitalService hospitalService;

    @Autowired
    private SpecialtyService specialtyService;

    @Autowired
    private DoctorService doctorService;

    @Autowired
    private SlotService slotService;

    @Autowired
    private PatientService patientService;

    @Autowired
    private TokenService tokenService;

    private record Session(UUID doctorId, UUID slotId, LocalDate date) {
    }

    private Session createSession(int capacity) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        HospitalResponse hospital = hospitalService.create(
                new CreateHospitalRequest("Concurrency Hospital " + suffix, "Test address " + suffix));
        SpecialtyResponse specialty = specialtyService.create(new CreateSpecialtyRequest("Specialty " + suffix));
        DoctorResponse doctor = doctorService.create(hospital.id(),
                new CreateDoctorRequest(specialty.id(), "Dr. Test " + suffix, "Test Council", "REG-" + suffix));
        LocalDate date = LocalDate.now(ClockConfig.BUSINESS_ZONE).plusDays(1);
        SlotResponse slot = slotService.create(doctor.id(),
                new CreateSlotRequest(date, LocalTime.of(9, 0), LocalTime.of(12, 0), capacity));
        return new Session(doctor.id(), slot.id(), date);
    }

    private UUID registerPatient(int offset) {
        long base = ThreadLocalRandom.current().nextLong(0, 800_000_000L);
        String phone = String.format("9%09d", base + offset);
        return patientService.register(new RegisterPatientRequest(phone, null)).id();
    }

    @Test
    void manyPatientsCompetingForFewTokens_neverOversellAndNeverRepeatANumber() throws Exception {
        int capacity = 5;
        int contenders = 12;
        Session session = createSession(capacity);
        List<UUID> patients = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            patients.add(registerPatient(i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (UUID patientId : patients) {
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    return "OK:" + tokenService.book(patientId, session.slotId()).token().tokenNumber();
                } catch (ApiException failure) {
                    return failure.getCode();
                }
            }));
        }
        go.countDown();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        List<Integer> numbers = outcomes.stream()
                .filter(outcome -> outcome.startsWith("OK:"))
                .map(outcome -> Integer.parseInt(outcome.substring(3)))
                .sorted()
                .toList();
        List<String> failures = outcomes.stream().filter(outcome -> !outcome.startsWith("OK:")).toList();

        assertThat(numbers).containsExactly(1, 2, 3, 4, 5);
        assertThat(failures).hasSize(contenders - capacity).containsOnly("SLOT_FULL");

        SlotResponse slot = slotService.list(session.doctorId(), session.date()).get(0);
        assertThat(slot.bookedCount()).isEqualTo(capacity);
        assertThat(slot.remainingTokens()).isZero();
    }

    @Test
    void simultaneousIdenticalRequestsFromOnePatientCreateExactlyOneToken() throws Exception {
        int attempts = 10;
        Session session = createSession(30);
        UUID patientId = registerPatient(0);

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BookingResult>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return tokenService.book(patientId, session.slotId());
            }));
        }
        go.countDown();

        List<BookingResult> results = new ArrayList<>();
        for (Future<BookingResult> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        Set<UUID> distinctTokenIds = new HashSet<>();
        results.forEach(result -> distinctTokenIds.add(result.token().id()));
        long createdCount = results.stream().filter(BookingResult::created).count();

        assertThat(distinctTokenIds).hasSize(1);
        assertThat(createdCount).isEqualTo(1);

        SlotResponse slot = slotService.list(session.doctorId(), session.date()).get(0);
        assertThat(slot.bookedCount()).isEqualTo(1);
    }
}
