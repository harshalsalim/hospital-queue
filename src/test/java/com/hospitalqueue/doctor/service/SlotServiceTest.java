package com.hospitalqueue.doctor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.dto.CreateSlotRequest;
import com.hospitalqueue.doctor.dto.SlotResponse;
import com.hospitalqueue.doctor.entity.Doctor;
import com.hospitalqueue.doctor.entity.Slot;
import com.hospitalqueue.doctor.entity.SlotStatus;
import com.hospitalqueue.doctor.repository.DoctorRepository;
import com.hospitalqueue.doctor.repository.SlotRepository;

/**
 * Unit tests for {@link SlotService} with a fixed clock: 2026-11-12 10:00 in Asia/Kolkata.
 * Repositories are faked, so no database or Spring is needed.
 */
@ExtendWith(MockitoExtension.class)
class SlotServiceTest {

    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID SLOT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T04:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    @Mock
    private SlotRepository slotRepository;

    @Mock
    private DoctorRepository doctorRepository;

    private SlotService slotService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Kolkata"));
        slotService = new SlotService(slotRepository, doctorRepository, clock);
    }

    private static Doctor doctor() {
        return new Doctor(HOSPITAL_ID, UUID.randomUUID(), "Dr. Asha Nair", "Kerala Medical Council", "KMC1");
    }

    private void doctorExists() {
        when(doctorRepository.findById(DOCTOR_ID)).thenReturn(Optional.of(doctor()));
    }

    private static CreateSlotRequest request(LocalDate date, LocalTime start, LocalTime end, int maxTokens) {
        return new CreateSlotRequest(date, start, end, maxTokens);
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException exception = (ApiException) thrown;
        assertThat(exception.getStatus()).isEqualTo(status);
        assertThat(exception.getCode()).isEqualTo(code);
    }

    // ---- create ----

    @Test
    void create_savesAnOpenSessionWithTheDoctorsHospitalAndWholeMinutes() {
        doctorExists();
        LocalDate date = TODAY.plusDays(1);
        when(slotRepository.existsOverlapping(DOCTOR_ID, date, LocalTime.of(9, 0), LocalTime.of(12, 0))).thenReturn(false);
        when(slotRepository.save(any(Slot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SlotResponse response = slotService.create(DOCTOR_ID,
                request(date, LocalTime.of(9, 0, 30), LocalTime.of(12, 0, 45), 30));

        ArgumentCaptor<Slot> saved = ArgumentCaptor.forClass(Slot.class);
        verify(slotRepository).save(saved.capture());
        assertThat(saved.getValue().getHospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(saved.getValue().getDoctorId()).isEqualTo(DOCTOR_ID);
        assertThat(saved.getValue().getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(saved.getValue().getEndTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(response.maxTokens()).isEqualTo(30);
        assertThat(response.bookedCount()).isZero();
        assertThat(response.remainingTokens()).isEqualTo(30);
        assertThat(response.status()).isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    void create_unknownDoctorThrowsNotFoundAndSavesNothing() {
        when(doctorRepository.findById(DOCTOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> slotService.create(DOCTOR_ID,
                request(TODAY.plusDays(1), LocalTime.of(9, 0), LocalTime.of(12, 0), 30)))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));

        verify(slotRepository, never()).save(any(Slot.class));
    }

    @Test
    void create_endNotAfterStartIsRejected() {
        doctorExists();

        assertThatThrownBy(() -> slotService.create(DOCTOR_ID,
                request(TODAY.plusDays(1), LocalTime.of(9, 0), LocalTime.of(9, 0), 30)))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE"));

        verify(slotRepository, never()).save(any(Slot.class));
    }

    @Test
    void create_aDateInThePastIsRejected() {
        doctorExists();

        assertThatThrownBy(() -> slotService.create(DOCTOR_ID,
                request(TODAY.minusDays(1), LocalTime.of(9, 0), LocalTime.of(12, 0), 30)))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.BAD_REQUEST, "SLOT_IN_PAST"));

        verify(slotRepository, never()).save(any(Slot.class));
    }

    @Test
    void create_aSessionTodayThatHasAlreadyEndedIsRejected() {
        doctorExists();

        assertThatThrownBy(() -> slotService.create(DOCTOR_ID,
                request(TODAY, LocalTime.of(8, 0), LocalTime.of(9, 30), 30)))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.BAD_REQUEST, "SLOT_IN_PAST"));

        verify(slotRepository, never()).save(any(Slot.class));
    }

    @Test
    void create_aSessionTodayThatIsStillRunningIsAllowed() {
        doctorExists();
        when(slotRepository.existsOverlapping(DOCTOR_ID, TODAY, LocalTime.of(9, 0), LocalTime.of(10, 30))).thenReturn(false);
        when(slotRepository.save(any(Slot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SlotResponse response = slotService.create(DOCTOR_ID,
                request(TODAY, LocalTime.of(9, 0), LocalTime.of(10, 30), 20));

        assertThat(response.date()).isEqualTo(TODAY);
    }

    @Test
    void create_moreThanNinetyDaysAheadIsRejected() {
        doctorExists();

        assertThatThrownBy(() -> slotService.create(DOCTOR_ID,
                request(TODAY.plusDays(SlotService.MAX_DAYS_AHEAD + 1), LocalTime.of(9, 0), LocalTime.of(12, 0), 30)))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.BAD_REQUEST, "SLOT_TOO_FAR"));

        verify(slotRepository, never()).save(any(Slot.class));
    }

    @Test
    void create_exactlyNinetyDaysAheadIsAllowed() {
        doctorExists();
        LocalDate lastAllowed = TODAY.plusDays(SlotService.MAX_DAYS_AHEAD);
        when(slotRepository.existsOverlapping(DOCTOR_ID, lastAllowed, LocalTime.of(9, 0), LocalTime.of(12, 0))).thenReturn(false);
        when(slotRepository.save(any(Slot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SlotResponse response = slotService.create(DOCTOR_ID,
                request(lastAllowed, LocalTime.of(9, 0), LocalTime.of(12, 0), 30));

        assertThat(response.date()).isEqualTo(lastAllowed);
    }

    @Test
    void create_anOverlappingSessionIsRejectedAndNothingIsSaved() {
        doctorExists();
        LocalDate date = TODAY.plusDays(1);
        when(slotRepository.existsOverlapping(DOCTOR_ID, date, LocalTime.of(9, 0), LocalTime.of(12, 0))).thenReturn(true);

        assertThatThrownBy(() -> slotService.create(DOCTOR_ID,
                request(date, LocalTime.of(9, 0), LocalTime.of(12, 0), 30)))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "SLOT_OVERLAP"));

        verify(slotRepository, never()).save(any(Slot.class));
    }

    // ---- list ----

    @Test
    void list_unknownDoctorThrowsNotFound() {
        when(doctorRepository.existsById(DOCTOR_ID)).thenReturn(false);

        assertThatThrownBy(() -> slotService.list(DOCTOR_ID, TODAY))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }

    @Test
    void list_withoutADateUsesTodayInTheBusinessTimeZone() {
        when(doctorRepository.existsById(DOCTOR_ID)).thenReturn(true);
        when(slotRepository.findByDoctorIdAndSlotDateOrderByStartTimeAsc(DOCTOR_ID, TODAY)).thenReturn(List.of());

        List<SlotResponse> result = slotService.list(DOCTOR_ID, null);

        assertThat(result).isEmpty();
        verify(slotRepository).findByDoctorIdAndSlotDateOrderByStartTimeAsc(DOCTOR_ID, TODAY);
    }

    @Test
    void list_mapsRemainingTokensFromMaxAndBooked() {
        LocalDate date = TODAY.plusDays(2);
        Slot slot = new Slot(HOSPITAL_ID, DOCTOR_ID, date, LocalTime.of(9, 0), LocalTime.of(12, 0), 30);
        ReflectionTestUtils.setField(slot, "bookedCount", 7);
        when(doctorRepository.existsById(DOCTOR_ID)).thenReturn(true);
        when(slotRepository.findByDoctorIdAndSlotDateOrderByStartTimeAsc(DOCTOR_ID, date)).thenReturn(List.of(slot));

        List<SlotResponse> result = slotService.list(DOCTOR_ID, date);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).maxTokens()).isEqualTo(30);
        assertThat(result.get(0).bookedCount()).isEqualTo(7);
        assertThat(result.get(0).remainingTokens()).isEqualTo(23);
    }

    // ---- get ----

    @Test
    void get_returnsTheSessionIncludingItsDoctorAndHospital() {
        Slot slot = new Slot(HOSPITAL_ID, DOCTOR_ID, TODAY.plusDays(1), LocalTime.of(9, 0), LocalTime.of(12, 0), 30);
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot));

        SlotResponse response = slotService.get(SLOT_ID);

        assertThat(response.doctorId()).isEqualTo(DOCTOR_ID);
        assertThat(response.hospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(response.maxTokens()).isEqualTo(30);
    }

    @Test
    void get_unknownSessionThrowsNotFound() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> slotService.get(SLOT_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }

    // ---- exists ----

    @Test
    void exists_delegatesToTheRepository() {
        UUID unknown = UUID.randomUUID();
        when(slotRepository.existsById(SLOT_ID)).thenReturn(true);
        when(slotRepository.existsById(unknown)).thenReturn(false);

        assertThat(slotService.exists(SLOT_ID)).isTrue();
        assertThat(slotService.exists(unknown)).isFalse();
    }

    // ---- updateStatus ----

    @Test
    void updateStatus_changesTheStatusAndReturnsTheReloadedSlot() {
        Slot slot = new Slot(HOSPITAL_ID, DOCTOR_ID, TODAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30);
        ReflectionTestUtils.setField(slot, "status", SlotStatus.PAUSED);
        when(slotRepository.updateStatus(SLOT_ID, "PAUSED", NOW)).thenReturn(1);
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot));

        SlotResponse response = slotService.updateStatus(SLOT_ID, SlotStatus.PAUSED);

        assertThat(response.status()).isEqualTo(SlotStatus.PAUSED);
        verify(slotRepository).updateStatus(SLOT_ID, "PAUSED", NOW);
    }

    @Test
    void updateStatus_unknownSlotThrowsNotFound() {
        when(slotRepository.updateStatus(eq(SLOT_ID), eq("PAUSED"), any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> slotService.updateStatus(SLOT_ID, SlotStatus.PAUSED))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }

    // ---- updateCapacity ----

    @Test
    void updateCapacity_changesTheLimitAndReturnsTheReloadedSlot() {
        Slot slot = new Slot(HOSPITAL_ID, DOCTOR_ID, TODAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 40);
        when(slotRepository.updateMaxTokens(SLOT_ID, 40, NOW)).thenReturn(1);
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot));

        SlotResponse response = slotService.updateCapacity(SLOT_ID, 40);

        assertThat(response.maxTokens()).isEqualTo(40);
        verify(slotRepository).updateMaxTokens(SLOT_ID, 40, NOW);
    }

    @Test
    void updateCapacity_belowTheBookedCountThrowsConflict() {
        when(slotRepository.updateMaxTokens(SLOT_ID, 5, NOW)).thenReturn(0);
        when(slotRepository.existsById(SLOT_ID)).thenReturn(true);

        assertThatThrownBy(() -> slotService.updateCapacity(SLOT_ID, 5))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "CAPACITY_BELOW_BOOKED"));
    }

    @Test
    void updateCapacity_unknownSlotThrowsNotFound() {
        when(slotRepository.updateMaxTokens(SLOT_ID, 5, NOW)).thenReturn(0);
        when(slotRepository.existsById(SLOT_ID)).thenReturn(false);

        assertThatThrownBy(() -> slotService.updateCapacity(SLOT_ID, 5))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }
}
