package com.hospitalqueue.doctor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.dto.TokenAllocation;
import com.hospitalqueue.doctor.entity.Slot;
import com.hospitalqueue.doctor.repository.SlotCounterRepository;
import com.hospitalqueue.doctor.repository.SlotCounterRepository.SlotState;
import com.hospitalqueue.doctor.repository.SlotRepository;

/**
 * Unit tests for {@link SlotBookingService} with a fixed clock: 2026-11-12 10:00 in Asia/Kolkata.
 * The atomic SQL itself is proven against a real database in TokenBookingConcurrencyTest.
 */
@ExtendWith(MockitoExtension.class)
class SlotBookingServiceTest {

    private static final UUID SLOT_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    @Mock
    private SlotRepository slotRepository;

    @Mock
    private SlotCounterRepository slotCounterRepository;

    private SlotBookingService slotBookingService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-12T04:30:00Z"), ZoneId.of("Asia/Kolkata"));
        slotBookingService = new SlotBookingService(slotRepository, slotCounterRepository, clock);
    }

    private void slotExists(LocalDate date, LocalTime start, LocalTime end) {
        when(slotRepository.findById(SLOT_ID))
                .thenReturn(Optional.of(new Slot(HOSPITAL_ID, DOCTOR_ID, date, start, end, 30)));
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException exception = (ApiException) thrown;
        assertThat(exception.getStatus()).isEqualTo(status);
        assertThat(exception.getCode()).isEqualTo(code);
    }

    @Test
    void takeToken_returnsTheReservedNumberWithTheSessionsHospitalAndDoctor() {
        slotExists(TODAY.plusDays(1), LocalTime.of(9, 0), LocalTime.of(12, 0));
        when(slotCounterRepository.takeNextToken(SLOT_ID)).thenReturn(OptionalInt.of(4));

        TokenAllocation allocation = slotBookingService.takeToken(SLOT_ID);

        assertThat(allocation.tokenNumber()).isEqualTo(4);
        assertThat(allocation.slotId()).isEqualTo(SLOT_ID);
        assertThat(allocation.hospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(allocation.doctorId()).isEqualTo(DOCTOR_ID);
    }

    @Test
    void takeToken_aSessionThatIsStillRunningAcceptsBookings() {
        slotExists(TODAY, LocalTime.of(9, 0), LocalTime.of(12, 0));
        when(slotCounterRepository.takeNextToken(SLOT_ID)).thenReturn(OptionalInt.of(1));

        assertThat(slotBookingService.takeToken(SLOT_ID).tokenNumber()).isEqualTo(1);
    }

    @Test
    void takeToken_unknownSlotThrowsNotFoundWithoutTouchingTheCounter() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> slotBookingService.takeToken(SLOT_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));

        verifyNoInteractions(slotCounterRepository);
    }

    @Test
    void takeToken_aSessionThatHasEndedIsRejectedWithoutTouchingTheCounter() {
        slotExists(TODAY, LocalTime.of(8, 0), LocalTime.of(9, 30));

        assertThatThrownBy(() -> slotBookingService.takeToken(SLOT_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "SLOT_ENDED"));

        verifyNoInteractions(slotCounterRepository);
    }

    @Test
    void takeToken_aSessionOfAPastDayIsRejected() {
        slotExists(TODAY.minusDays(1), LocalTime.of(9, 0), LocalTime.of(12, 0));

        assertThatThrownBy(() -> slotBookingService.takeToken(SLOT_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "SLOT_ENDED"));
    }

    @Test
    void takeToken_aPausedSessionIsReportedAsPaused() {
        slotExists(TODAY.plusDays(1), LocalTime.of(9, 0), LocalTime.of(12, 0));
        when(slotCounterRepository.takeNextToken(SLOT_ID)).thenReturn(OptionalInt.empty());
        when(slotCounterRepository.readState(SLOT_ID)).thenReturn(Optional.of(new SlotState("PAUSED", 3, 30)));

        assertThatThrownBy(() -> slotBookingService.takeToken(SLOT_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "SLOT_PAUSED"));
    }

    @Test
    void takeToken_aFullSessionIsReportedAsFull() {
        slotExists(TODAY.plusDays(1), LocalTime.of(9, 0), LocalTime.of(12, 0));
        when(slotCounterRepository.takeNextToken(SLOT_ID)).thenReturn(OptionalInt.empty());
        when(slotCounterRepository.readState(SLOT_ID)).thenReturn(Optional.of(new SlotState("AVAILABLE", 30, 30)));

        assertThatThrownBy(() -> slotBookingService.takeToken(SLOT_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "SLOT_FULL"));
    }

    @Test
    void takeToken_aSessionDeletedDuringTheRequestIsReportedAsNotFound() {
        slotExists(TODAY.plusDays(1), LocalTime.of(9, 0), LocalTime.of(12, 0));
        when(slotCounterRepository.takeNextToken(SLOT_ID)).thenReturn(OptionalInt.empty());
        when(slotCounterRepository.readState(SLOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> slotBookingService.takeToken(SLOT_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }

    @Test
    void takeToken_aStateThatChangedDuringTheRequestAsksTheCallerToRetry() {
        slotExists(TODAY.plusDays(1), LocalTime.of(9, 0), LocalTime.of(12, 0));
        when(slotCounterRepository.takeNextToken(SLOT_ID)).thenReturn(OptionalInt.empty());
        when(slotCounterRepository.readState(SLOT_ID)).thenReturn(Optional.of(new SlotState("AVAILABLE", 10, 30)));

        assertThatThrownBy(() -> slotBookingService.takeToken(SLOT_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "SLOT_UNAVAILABLE"));

        verify(slotCounterRepository).takeNextToken(SLOT_ID);
    }
}
