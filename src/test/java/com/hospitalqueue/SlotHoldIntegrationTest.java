package com.hospitalqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
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
import com.hospitalqueue.doctor.service.SlotHoldService;
import com.hospitalqueue.doctor.service.SlotService;
import com.hospitalqueue.patient.dto.RegisterPatientRequest;
import com.hospitalqueue.patient.service.PatientService;
import com.hospitalqueue.patient.service.TokenService;

/**
 * Proves the Redis hold logic against a REAL Redis and database (Docker must be running): the Lua script never
 * grants more holds than there are free tokens, released and lapsed holds free their place, and real bookings
 * reduce what can be held. Holds last only 2 seconds here so lapsing can be tested quickly.
 */
@SpringBootTest(properties = "app.booking.hold-seconds=2")
@Import(TestcontainersConfiguration.class)
class SlotHoldIntegrationTest {

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

    @Autowired
    private SlotHoldService slotHoldService;

    private UUID createSlot(int capacity) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        HospitalResponse hospital = hospitalService.create(
                new CreateHospitalRequest("Hold Hospital " + suffix, "Test address " + suffix));
        SpecialtyResponse specialty = specialtyService.create(new CreateSpecialtyRequest("Hold Specialty " + suffix));
        DoctorResponse doctor = doctorService.create(hospital.id(),
                new CreateDoctorRequest(specialty.id(), "Dr. Hold " + suffix, "Test Council", "HOLD-" + suffix));
        LocalDate date = LocalDate.now(ClockConfig.BUSINESS_ZONE).plusDays(1);
        SlotResponse slot = slotService.create(doctor.id(),
                new CreateSlotRequest(date, LocalTime.of(9, 0), LocalTime.of(12, 0), capacity));
        return slot.id();
    }

    private static String codeOf(Throwable failure) {
        return failure instanceof ApiException apiException ? apiException.getCode() : failure.getClass().getSimpleName();
    }

    @Test
    void concurrentHolds_neverExceedTheFreeTokens() throws Exception {
        UUID slotId = createSlot(3);
        int contenders = 10;

        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    slotHoldService.acquire(slotId, UUID.randomUUID());
                    return "HELD";
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

        assertThat(outcomes.stream().filter("HELD"::equals).count()).isEqualTo(3);
        assertThat(outcomes.stream().filter(outcome -> !"HELD".equals(outcome)))
                .hasSize(contenders - 3)
                .containsOnly("SLOT_TEMPORARILY_FULL");
    }

    @Test
    void releasingAHoldFreesItsPlaceForTheNextPatient() {
        UUID slotId = createSlot(2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        slotHoldService.acquire(slotId, first);
        slotHoldService.acquire(slotId, second);
        assertThatThrownBy(() -> slotHoldService.acquire(slotId, third))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("SLOT_TEMPORARILY_FULL"));

        slotHoldService.release(slotId, first);

        assertThat(slotHoldService.acquire(slotId, third).holdId()).isEqualTo(third);
    }

    @Test
    void aHoldLapsesByItselfAfterItsTime() throws Exception {
        UUID slotId = createSlot(1);
        UUID first = UUID.randomUUID();

        slotHoldService.acquire(slotId, first);
        assertThat(slotHoldService.expiryOf(slotId, first)).isPresent();
        assertThatThrownBy(() -> slotHoldService.acquire(slotId, UUID.randomUUID()))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("SLOT_TEMPORARILY_FULL"));

        Thread.sleep(2500);

        assertThat(slotHoldService.expiryOf(slotId, first)).isEmpty();
        UUID next = UUID.randomUUID();
        assertThat(slotHoldService.acquire(slotId, next).holdId()).isEqualTo(next);
    }

    @Test
    void expiryOf_isEmptyAfterTheHoldIsReleased() {
        UUID slotId = createSlot(2);
        UUID holdId = UUID.randomUUID();

        slotHoldService.acquire(slotId, holdId);
        assertThat(slotHoldService.expiryOf(slotId, holdId)).isPresent();

        slotHoldService.release(slotId, holdId);
        assertThat(slotHoldService.expiryOf(slotId, holdId)).isEmpty();
    }

    @Test
    void holdsOnlyCoverTokensThatAreNotYetBooked() {
        UUID slotId = createSlot(3);
        long base = ThreadLocalRandom.current().nextLong(0, 800_000_000L);
        UUID patientId = patientService.register(new RegisterPatientRequest(String.format("9%09d", base), null)).id();
        tokenService.book(patientId, slotId);

        slotHoldService.acquire(slotId, UUID.randomUUID());
        slotHoldService.acquire(slotId, UUID.randomUUID());

        assertThatThrownBy(() -> slotHoldService.acquire(slotId, UUID.randomUUID()))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("SLOT_TEMPORARILY_FULL"));
    }
}
