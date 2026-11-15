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
import com.hospitalqueue.doctor.service.SlotService;
import com.hospitalqueue.patient.dto.RegisterPatientRequest;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.service.PatientService;
import com.hospitalqueue.patient.service.QueueService;
import com.hospitalqueue.patient.service.TokenService;

/**
 * The doctor's queue against a REAL PostgreSQL (Docker must be running): patients are called in token order and only
 * once they have arrived, a doctor never has two patients at once even when "call next" is pressed on several devices
 * at the same moment, and steps that make no sense are refused.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class QueueIntegrationTest {

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
    private QueueService queueService;

    private UUID createSlot(int capacity) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        HospitalResponse hospital = hospitalService.create(
                new CreateHospitalRequest("Queue Hospital " + suffix, "Test address " + suffix));
        SpecialtyResponse specialty = specialtyService.create(new CreateSpecialtyRequest("Queue Specialty " + suffix));
        DoctorResponse doctor = doctorService.create(hospital.id(),
                new CreateDoctorRequest(specialty.id(), "Dr. Queue " + suffix, "Test Council", "QUE-" + suffix));
        LocalDate date = LocalDate.now(ClockConfig.BUSINESS_ZONE).plusDays(1);
        SlotResponse slot = slotService.create(doctor.id(),
                new CreateSlotRequest(date, LocalTime.of(9, 0), LocalTime.of(12, 0), capacity));
        return slot.id();
    }

    /** Books a token for a brand-new patient. Tokens are numbered 1, 2, 3... in the order they are booked. */
    private TokenResponse bookToken(UUID slotId) {
        long number = ThreadLocalRandom.current().nextLong(0, 900_000_000L);
        UUID patientId = patientService.register(new RegisterPatientRequest(String.format("9%09d", number), null)).id();
        return tokenService.book(patientId, slotId).token();
    }

    private static String codeOf(Throwable failure) {
        return failure instanceof ApiException apiException ? apiException.getCode() : failure.getClass().getSimpleName();
    }

    private TokenStatus statusOf(TokenResponse token) {
        return tokenService.get(token.id()).status();
    }

    @Test
    void theDoctorWorksThroughTheQueueInTokenOrderSkippingPatientsWhoHaveNotArrived() {
        UUID slotId = createSlot(5);
        TokenResponse first = bookToken(slotId);
        TokenResponse second = bookToken(slotId);
        TokenResponse third = bookToken(slotId);

        // The third patient arrives before the first; the second has not arrived at all.
        assertThat(queueService.checkIn(third.id()).status()).isEqualTo(TokenStatus.WAITING);
        assertThat(queueService.checkIn(first.id()).status()).isEqualTo(TokenStatus.WAITING);

        TokenResponse called = queueService.callNext(slotId);
        assertThat(called.id()).isEqualTo(first.id());
        assertThat(called.status()).isEqualTo(TokenStatus.IN_CONSULTATION);

        assertThatThrownBy(() -> queueService.callNext(slotId))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("CONSULTATION_IN_PROGRESS"));

        assertThat(queueService.complete(first.id()).status()).isEqualTo(TokenStatus.COMPLETED);
        assertThat(queueService.complete(first.id()).status()).isEqualTo(TokenStatus.COMPLETED);

        assertThat(queueService.callNext(slotId).id()).isEqualTo(third.id());
        queueService.complete(third.id());

        assertThatThrownBy(() -> queueService.callNext(slotId))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("QUEUE_EMPTY"));

        queueService.checkIn(second.id());
        assertThat(queueService.callNext(slotId).id()).isEqualTo(second.id());
        assertThat(queueService.markNoShow(second.id()).status()).isEqualTo(TokenStatus.NO_SHOW);

        assertThat(statusOf(first)).isEqualTo(TokenStatus.COMPLETED);
        assertThat(statusOf(second)).isEqualTo(TokenStatus.NO_SHOW);
        assertThat(statusOf(third)).isEqualTo(TokenStatus.COMPLETED);
    }

    @Test
    void patientsAreCalledInTokenOrderWhateverOrderTheyArrivedIn() {
        UUID slotId = createSlot(5);
        TokenResponse first = bookToken(slotId);
        TokenResponse second = bookToken(slotId);
        TokenResponse third = bookToken(slotId);

        queueService.checkIn(third.id());
        queueService.checkIn(second.id());
        queueService.checkIn(first.id());

        assertThat(queueService.callNext(slotId).tokenNumber()).isEqualTo(1);
        queueService.complete(first.id());
        assertThat(queueService.callNext(slotId).tokenNumber()).isEqualTo(2);
        queueService.complete(second.id());
        assertThat(queueService.callNext(slotId).tokenNumber()).isEqualTo(3);
    }

    @Test
    void simultaneousCallNextFromSeveralDevicesPutsExactlyOnePatientInConsultation() throws Exception {
        int waiting = 5;
        int devices = 6;
        UUID slotId = createSlot(waiting);
        List<TokenResponse> tokens = new ArrayList<>();
        for (int i = 0; i < waiting; i++) {
            TokenResponse token = bookToken(slotId);
            queueService.checkIn(token.id());
            tokens.add(token);
        }

        ExecutorService pool = Executors.newFixedThreadPool(devices);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    queueService.callNext(slotId);
                    return "CALLED";
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

        assertThat(outcomes.stream().filter("CALLED"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> !"CALLED".equals(outcome)))
                .hasSize(devices - 1)
                .containsOnly("CONSULTATION_IN_PROGRESS");

        long inConsultation = tokens.stream().filter(token -> statusOf(token) == TokenStatus.IN_CONSULTATION).count();
        long stillWaiting = tokens.stream().filter(token -> statusOf(token) == TokenStatus.WAITING).count();
        assertThat(inConsultation).isEqualTo(1);
        assertThat(stillWaiting).isEqualTo(waiting - 1);
        assertThat(statusOf(tokens.get(0))).isEqualTo(TokenStatus.IN_CONSULTATION);
    }

    @Test
    void stepsThatMakeNoSenseAreRefusedAndUnknownIdsAreNotFound() {
        UUID slotId = createSlot(3);
        TokenResponse token = bookToken(slotId);

        assertThatThrownBy(() -> queueService.complete(token.id()))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("INVALID_TOKEN_STATE"));
        assertThatThrownBy(() -> queueService.callNext(slotId))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("QUEUE_EMPTY"));

        queueService.checkIn(token.id());
        assertThat(queueService.checkIn(token.id()).status()).isEqualTo(TokenStatus.WAITING);
        assertThatThrownBy(() -> queueService.complete(token.id()))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("INVALID_TOKEN_STATE"));

        assertThat(queueService.markNoShow(token.id()).status()).isEqualTo(TokenStatus.NO_SHOW);
        assertThat(queueService.markNoShow(token.id()).status()).isEqualTo(TokenStatus.NO_SHOW);
        assertThatThrownBy(() -> queueService.checkIn(token.id()))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("INVALID_TOKEN_STATE"));

        assertThatThrownBy(() -> queueService.checkIn(UUID.randomUUID()))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> queueService.callNext(UUID.randomUUID()))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("NOT_FOUND"));
    }
}
