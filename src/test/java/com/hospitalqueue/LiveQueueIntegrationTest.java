package com.hospitalqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import com.hospitalqueue.patient.dto.QueueStatusResponse;
import com.hospitalqueue.patient.dto.RegisterPatientRequest;
import com.hospitalqueue.patient.dto.TokenPositionResponse;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.service.PatientService;
import com.hospitalqueue.patient.service.QueueService;
import com.hospitalqueue.patient.service.QueueStatusService;
import com.hospitalqueue.patient.service.TokenService;

/**
 * The live queue against a REAL PostgreSQL and Redis (Docker must be running). The cache lives for 3 seconds, so if a
 * queue change did not evict it, the views below would show stale numbers: these tests therefore also prove that every
 * change shows up at once.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LiveQueueIntegrationTest {

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

    @Autowired
    private QueueStatusService queueStatusService;

    private UUID createSlot(int capacity) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        HospitalResponse hospital = hospitalService.create(
                new CreateHospitalRequest("Live Queue Hospital " + suffix, "Test address " + suffix));
        SpecialtyResponse specialty = specialtyService.create(new CreateSpecialtyRequest("Live Queue Specialty " + suffix));
        DoctorResponse doctor = doctorService.create(hospital.id(),
                new CreateDoctorRequest(specialty.id(), "Dr. Live " + suffix, "Test Council", "LIV-" + suffix));
        LocalDate date = LocalDate.now(ClockConfig.BUSINESS_ZONE).plusDays(1);
        SlotResponse slot = slotService.create(doctor.id(),
                new CreateSlotRequest(date, LocalTime.of(9, 0), LocalTime.of(12, 0), capacity));
        return slot.id();
    }

    private TokenResponse bookToken(UUID slotId) {
        long number = ThreadLocalRandom.current().nextLong(0, 900_000_000L);
        UUID patientId = patientService.register(new RegisterPatientRequest(String.format("9%09d", number), null)).id();
        return tokenService.book(patientId, slotId).token();
    }

    private static String codeOf(Throwable failure) {
        return failure instanceof ApiException apiException ? apiException.getCode() : failure.getClass().getSimpleName();
    }

    @Test
    void aSessionWithNoBookingsShowsAnEmptyQueue() {
        UUID slotId = createSlot(3);

        QueueStatusResponse queue = queueStatusService.getSlotQueue(slotId);

        assertThat(queue.nowServingTokenNumber()).isNull();
        assertThat(queue.nextWaitingTokenNumber()).isNull();
        assertThat(queue.waitingCount()).isZero();
        assertThat(queue.bookedNotArrivedCount()).isZero();
        assertThat(queue.completedCount()).isZero();
    }

    @Test
    void theLiveQueueFollowsTheDoctorsActionsAtOnce() {
        UUID slotId = createSlot(6);
        TokenResponse first = bookToken(slotId);
        TokenResponse second = bookToken(slotId);
        TokenResponse third = bookToken(slotId);

        QueueStatusResponse beforeAnyone = queueStatusService.getSlotQueue(slotId);
        assertThat(beforeAnyone.nowServingTokenNumber()).isNull();
        assertThat(beforeAnyone.bookedNotArrivedCount()).isEqualTo(3);
        assertThat(beforeAnyone.waitingCount()).isZero();
        assertThat(beforeAnyone.averageConsultationMinutes()).isEqualTo(10);

        TokenPositionResponse thirdBefore = queueStatusService.getTokenPosition(slotId, third.id());
        assertThat(thirdBefore.status()).isEqualTo(TokenStatus.BOOKED);
        assertThat(thirdBefore.tokensAhead()).isEqualTo(2);
        assertThat(thirdBefore.estimatedWaitMinutes()).isEqualTo(20);

        queueService.checkIn(first.id());
        queueService.checkIn(second.id());
        QueueStatusResponse arrived = queueStatusService.getSlotQueue(slotId);
        assertThat(arrived.waitingCount()).isEqualTo(2);
        assertThat(arrived.bookedNotArrivedCount()).isEqualTo(1);
        assertThat(arrived.nextWaitingTokenNumber()).isEqualTo(1);
        assertThat(arrived.nowServingTokenNumber()).isNull();

        queueService.callNext(slotId);
        QueueStatusResponse serving = queueStatusService.getSlotQueue(slotId);
        assertThat(serving.nowServingTokenNumber()).isEqualTo(1);
        assertThat(serving.waitingCount()).isEqualTo(1);
        assertThat(serving.nextWaitingTokenNumber()).isEqualTo(2);

        TokenPositionResponse secondWhileFirstIsServed = queueStatusService.getTokenPosition(slotId, second.id());
        assertThat(secondWhileFirstIsServed.tokensAhead()).isEqualTo(1);
        assertThat(secondWhileFirstIsServed.estimatedWaitMinutes()).isEqualTo(10);
        assertThat(secondWhileFirstIsServed.nowServingTokenNumber()).isEqualTo(1);

        TokenPositionResponse beingServed = queueStatusService.getTokenPosition(slotId, first.id());
        assertThat(beingServed.status()).isEqualTo(TokenStatus.IN_CONSULTATION);
        assertThat(beingServed.tokensAhead()).isZero();
        assertThat(beingServed.estimatedWaitMinutes()).isZero();

        queueService.complete(first.id());
        QueueStatusResponse afterFirst = queueStatusService.getSlotQueue(slotId);
        assertThat(afterFirst.nowServingTokenNumber()).isNull();
        assertThat(afterFirst.completedCount()).isEqualTo(1);
        TokenPositionResponse secondNow = queueStatusService.getTokenPosition(slotId, second.id());
        assertThat(secondNow.tokensAhead()).isZero();
        assertThat(secondNow.estimatedWaitMinutes()).isZero();

        TokenPositionResponse finished = queueStatusService.getTokenPosition(slotId, first.id());
        assertThat(finished.status()).isEqualTo(TokenStatus.COMPLETED);
        assertThat(finished.tokensAhead()).isZero();
        assertThat(finished.estimatedWaitMinutes()).isNull();

        queueService.markNoShow(third.id());
        QueueStatusResponse afterNoShow = queueStatusService.getSlotQueue(slotId);
        assertThat(afterNoShow.noShowCount()).isEqualTo(1);
        assertThat(afterNoShow.bookedNotArrivedCount()).isZero();
    }

    @Test
    void aTokenBookedWhileTheQueueIsCachedStillGetsACorrectPositionAtOnce() {
        UUID slotId = createSlot(5);
        TokenResponse first = bookToken(slotId);
        TokenResponse second = bookToken(slotId);
        queueService.checkIn(first.id());
        queueService.checkIn(second.id());
        queueStatusService.getSlotQueue(slotId);

        TokenResponse third = bookToken(slotId);
        TokenPositionResponse position = queueStatusService.getTokenPosition(slotId, third.id());

        assertThat(position.status()).isEqualTo(TokenStatus.BOOKED);
        assertThat(position.tokensAhead()).isEqualTo(2);
        assertThat(position.estimatedWaitMinutes()).isEqualTo(20);
    }

    @Test
    void unknownSessionsAndTokensAndTokensOfOtherSessionsAreNotFound() {
        UUID slotA = createSlot(2);
        UUID slotB = createSlot(2);
        TokenResponse inB = bookToken(slotB);

        assertThatThrownBy(() -> queueStatusService.getSlotQueue(UUID.randomUUID()))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> queueStatusService.getTokenPosition(UUID.randomUUID(), inB.id()))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> queueStatusService.getTokenPosition(slotA, UUID.randomUUID()))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> queueStatusService.getTokenPosition(slotA, inB.id()))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("NOT_FOUND"));
    }
}
