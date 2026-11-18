package com.hospitalqueue.consultation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.consultation.dto.IssuePrescriptionRequest;
import com.hospitalqueue.consultation.dto.PrescriptionItemRequest;
import com.hospitalqueue.consultation.dto.PrescriptionItemResponse;
import com.hospitalqueue.consultation.dto.PrescriptionResponse;
import com.hospitalqueue.consultation.entity.Consultation;
import com.hospitalqueue.consultation.entity.Prescription;
import com.hospitalqueue.consultation.entity.PrescriptionItem;
import com.hospitalqueue.consultation.repository.ConsultationRepository;
import com.hospitalqueue.consultation.repository.PrescriptionItemRepository;
import com.hospitalqueue.consultation.repository.PrescriptionRepository;
import com.hospitalqueue.doctor.dto.DoctorResponse;
import com.hospitalqueue.doctor.service.DoctorService;
import com.hospitalqueue.patient.service.PatientService;

/**
 * Unit tests for {@link PrescriptionService}: repositories and other modules' services are faked; a real
 * TransactionTemplate runs on a fake transaction manager so rollbacks can be checked. The encryption and the database
 * guarantees are proven against a real database in PrescriptionIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class PrescriptionServiceTest {

    private static final UUID CONSULTATION_ID = UUID.randomUUID();
    private static final UUID PRESCRIPTION_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final UUID TOKEN_ID = UUID.randomUUID();

    @Mock
    private ConsultationRepository consultationRepository;

    @Mock
    private PrescriptionRepository prescriptionRepository;

    @Mock
    private PrescriptionItemRepository prescriptionItemRepository;

    @Mock
    private DoctorService doctorService;

    @Mock
    private PatientService patientService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private PrescriptionService prescriptionService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        prescriptionService = new PrescriptionService(consultationRepository, prescriptionRepository,
                prescriptionItemRepository, doctorService, patientService, new TransactionTemplate(transactionManager));
    }

    // ---- helpers ----

    private static Consultation consultation() {
        Consultation consultation = new Consultation(HOSPITAL_ID, PATIENT_ID, DOCTOR_ID, TOKEN_ID, null);
        ReflectionTestUtils.setField(consultation, "id", CONSULTATION_ID);
        return consultation;
    }

    private static DoctorResponse doctor() {
        return new DoctorResponse(DOCTOR_ID, HOSPITAL_ID, UUID.randomUUID(), "Dr. Asha Nair",
                "Kerala Medical Council", "KMC12345", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static PrescriptionItemRequest medicine(String name, String dosage, int days, int quantity) {
        return new PrescriptionItemRequest(name, dosage, days, quantity);
    }

    private static Prescription storedPrescription(UUID id, UUID consultationId, Instant createdAt) {
        Prescription prescription = new Prescription(HOSPITAL_ID, PATIENT_ID, DOCTOR_ID, consultationId,
                "Kerala Medical Council", "KMC12345", "Rest and fluids", "CBC");
        ReflectionTestUtils.setField(prescription, "id", id);
        ReflectionTestUtils.setField(prescription, "createdAt", createdAt);
        return prescription;
    }

    private static PrescriptionItem storedItem(UUID prescriptionId, int order, String name) {
        PrescriptionItem item = new PrescriptionItem(prescriptionId, order, name, "1 tablet twice a day", 5, 10);
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        return item;
    }

    private void consultationExists() {
        when(consultationRepository.findById(CONSULTATION_ID)).thenReturn(Optional.of(consultation()));
    }

    private void everythingIsReadyToSave() {
        consultationExists();
        when(prescriptionRepository.existsByConsultationId(CONSULTATION_ID)).thenReturn(false);
        when(doctorService.get(DOCTOR_ID)).thenReturn(doctor());
        when(prescriptionRepository.saveAndFlush(any(Prescription.class))).thenAnswer(invocation -> {
            Prescription saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", PRESCRIPTION_ID);
            ReflectionTestUtils.setField(saved, "createdAt", Instant.parse("2026-11-12T05:00:00Z"));
            return saved;
        });
        when(prescriptionItemRepository.saveAll(anyIterable())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException exception = (ApiException) thrown;
        assertThat(exception.getStatus()).isEqualTo(status);
        assertThat(exception.getCode()).isEqualTo(code);
    }

    // ---- issue: success ----

    @Test
    @SuppressWarnings("unchecked")
    void issue_savesThePrescriptionWithTheDoctorsRegistrationAndTheMedicinesInOrder() {
        everythingIsReadyToSave();

        PrescriptionResponse response = prescriptionService.issue(CONSULTATION_ID, new IssuePrescriptionRequest(
                "  Drink plenty of water  ", "CBC", List.of(
                        medicine("  Paracetamol 500mg  ", " 1 tablet twice a day ", 5, 10),
                        medicine("Cetirizine 10mg", "1 tablet at night", 3, 3))));

        ArgumentCaptor<Prescription> savedPrescription = ArgumentCaptor.forClass(Prescription.class);
        verify(prescriptionRepository).saveAndFlush(savedPrescription.capture());
        Prescription prescription = savedPrescription.getValue();
        assertThat(prescription.getHospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(prescription.getPatientId()).isEqualTo(PATIENT_ID);
        assertThat(prescription.getDoctorId()).isEqualTo(DOCTOR_ID);
        assertThat(prescription.getConsultationId()).isEqualTo(CONSULTATION_ID);
        assertThat(prescription.getDoctorRegistrationCouncil()).isEqualTo("Kerala Medical Council");
        assertThat(prescription.getDoctorRegistrationNumber()).isEqualTo("KMC12345");
        assertThat(prescription.getTherapyAdvice()).isEqualTo("Drink plenty of water");
        assertThat(prescription.getLabTests()).isEqualTo("CBC");

        ArgumentCaptor<Iterable<PrescriptionItem>> savedItems = ArgumentCaptor.forClass(Iterable.class);
        verify(prescriptionItemRepository).saveAll(savedItems.capture());
        List<PrescriptionItem> lines = new ArrayList<>();
        savedItems.getValue().forEach(lines::add);
        assertThat(lines).extracting(PrescriptionItem::getSortOrder).containsExactly(0, 1);
        assertThat(lines).extracting(PrescriptionItem::getMedicineName).containsExactly("Paracetamol 500mg", "Cetirizine 10mg");
        assertThat(lines).extracting(PrescriptionItem::getDosage).containsExactly("1 tablet twice a day", "1 tablet at night");
        assertThat(lines).allSatisfy(line -> assertThat(line.getPrescriptionId()).isEqualTo(PRESCRIPTION_ID));

        assertThat(response.id()).isEqualTo(PRESCRIPTION_ID);
        assertThat(response.doctorRegistrationNumber()).isEqualTo("KMC12345");
        assertThat(response.items()).extracting(PrescriptionItemResponse::medicineName)
                .containsExactly("Paracetamol 500mg", "Cetirizine 10mg");
        verify(transactionManager).commit(any());
    }

    @Test
    void issue_therapyAdviceAloneIsEnough() {
        everythingIsReadyToSave();

        PrescriptionResponse response = prescriptionService.issue(CONSULTATION_ID,
                new IssuePrescriptionRequest("Physiotherapy for ten days", null, null));

        assertThat(response.therapyAdvice()).isEqualTo("Physiotherapy for ten days");
        assertThat(response.labTests()).isNull();
        assertThat(response.items()).isEmpty();
    }

    @Test
    void issue_labTestsAloneAreEnough() {
        everythingIsReadyToSave();

        PrescriptionResponse response = prescriptionService.issue(CONSULTATION_ID,
                new IssuePrescriptionRequest("   ", "Blood sugar fasting", List.of()));

        assertThat(response.labTests()).isEqualTo("Blood sugar fasting");
        assertThat(response.therapyAdvice()).isNull();
    }

    @Test
    void issue_medicinesAloneAreEnoughAndBlankTextBecomesNone() {
        everythingIsReadyToSave();

        PrescriptionResponse response = prescriptionService.issue(CONSULTATION_ID, new IssuePrescriptionRequest(
                "  ", "  ", List.of(medicine("Paracetamol", "1 tablet", 3, 6))));

        assertThat(response.therapyAdvice()).isNull();
        assertThat(response.labTests()).isNull();
        assertThat(response.items()).hasSize(1);
    }

    // ---- issue: refusals ----

    @Test
    void issue_aPrescriptionWithNothingInItIsRefused() {
        consultationExists();

        assertThatThrownBy(() -> prescriptionService.issue(CONSULTATION_ID, new IssuePrescriptionRequest("  ", null, List.of())))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.BAD_REQUEST, "EMPTY_PRESCRIPTION"));
        assertThatThrownBy(() -> prescriptionService.issue(CONSULTATION_ID, new IssuePrescriptionRequest(null, null, null)))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.BAD_REQUEST, "EMPTY_PRESCRIPTION"));

        verify(prescriptionRepository, never()).saveAndFlush(any(Prescription.class));
        verifyNoInteractions(doctorService);
    }

    @Test
    void issue_unknownConsultationThrowsNotFoundWithoutTouchingAnythingElse() {
        when(consultationRepository.findById(CONSULTATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> prescriptionService.issue(CONSULTATION_ID,
                new IssuePrescriptionRequest("advice", null, null)))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));

        verifyNoInteractions(prescriptionRepository, doctorService);
    }

    @Test
    void issue_aSecondPrescriptionForTheSameConsultationIsRefused() {
        consultationExists();
        when(prescriptionRepository.existsByConsultationId(CONSULTATION_ID)).thenReturn(true);

        assertThatThrownBy(() -> prescriptionService.issue(CONSULTATION_ID,
                new IssuePrescriptionRequest("advice", null, null)))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "PRESCRIPTION_EXISTS"));

        verify(prescriptionRepository, never()).saveAndFlush(any(Prescription.class));
        verifyNoInteractions(doctorService);
    }

    @Test
    void issue_aSimultaneousRequestThatLostTheRaceIsToldAPrescriptionExistsAndNothingIsKept() {
        consultationExists();
        when(prescriptionRepository.existsByConsultationId(CONSULTATION_ID)).thenReturn(false, true);
        when(doctorService.get(DOCTOR_ID)).thenReturn(doctor());
        when(prescriptionRepository.saveAndFlush(any(Prescription.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key (consultation_id)"));

        assertThatThrownBy(() -> prescriptionService.issue(CONSULTATION_ID,
                new IssuePrescriptionRequest("advice", null, null)))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "PRESCRIPTION_EXISTS"));

        verify(transactionManager).rollback(any());
        verify(prescriptionItemRepository, never()).saveAll(anyIterable());
    }

    @Test
    void issue_aDatabaseConflictThatIsNotADuplicateIsRethrown() {
        consultationExists();
        when(prescriptionRepository.existsByConsultationId(CONSULTATION_ID)).thenReturn(false, false);
        when(doctorService.get(DOCTOR_ID)).thenReturn(doctor());
        when(prescriptionRepository.saveAndFlush(any(Prescription.class)))
                .thenThrow(new DataIntegrityViolationException("foreign key violation"));

        assertThatThrownBy(() -> prescriptionService.issue(CONSULTATION_ID,
                new IssuePrescriptionRequest("advice", null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void issue_ifSavingTheMedicinesFailsEverythingIsRolledBackSoThereIsNeverAPrescriptionWithoutItsMedicines() {
        everythingIsReadyToSave();
        when(prescriptionItemRepository.saveAll(anyIterable())).thenThrow(new IllegalStateException("database is down"));

        assertThatThrownBy(() -> prescriptionService.issue(CONSULTATION_ID, new IssuePrescriptionRequest(
                null, null, List.of(medicine("Paracetamol", "1 tablet", 3, 6)))))
                .isInstanceOf(IllegalStateException.class);

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    // ---- get ----

    @Test
    void get_returnsThePrescriptionWithItsMedicinesInTheDoctorsOrder() {
        Prescription prescription = storedPrescription(PRESCRIPTION_ID, CONSULTATION_ID, Instant.parse("2026-11-12T05:00:00Z"));
        when(prescriptionRepository.findById(PRESCRIPTION_ID)).thenReturn(Optional.of(prescription));
        when(prescriptionItemRepository.findByPrescriptionIdIn(List.of(PRESCRIPTION_ID))).thenReturn(List.of(
                storedItem(PRESCRIPTION_ID, 2, "Third"),
                storedItem(PRESCRIPTION_ID, 0, "First"),
                storedItem(PRESCRIPTION_ID, 1, "Second")));

        PrescriptionResponse response = prescriptionService.get(PRESCRIPTION_ID);

        assertThat(response.items()).extracting(PrescriptionItemResponse::medicineName)
                .containsExactly("First", "Second", "Third");
        assertThat(response.therapyAdvice()).isEqualTo("Rest and fluids");
        assertThat(response.doctorRegistrationCouncil()).isEqualTo("Kerala Medical Council");
    }

    @Test
    void get_unknownPrescriptionThrowsNotFound() {
        when(prescriptionRepository.findById(PRESCRIPTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> prescriptionService.get(PRESCRIPTION_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }

    // ---- getByConsultation ----

    @Test
    void getByConsultation_returnsThePrescriptionOfTheConsultation() {
        Prescription prescription = storedPrescription(PRESCRIPTION_ID, CONSULTATION_ID, Instant.parse("2026-11-12T05:00:00Z"));
        when(prescriptionRepository.findByConsultationId(CONSULTATION_ID)).thenReturn(Optional.of(prescription));
        when(prescriptionItemRepository.findByPrescriptionIdIn(List.of(PRESCRIPTION_ID)))
                .thenReturn(List.of(storedItem(PRESCRIPTION_ID, 0, "Paracetamol")));

        PrescriptionResponse response = prescriptionService.getByConsultation(CONSULTATION_ID);

        assertThat(response.id()).isEqualTo(PRESCRIPTION_ID);
        assertThat(response.items()).hasSize(1);
    }

    @Test
    void getByConsultation_withNoPrescriptionThrowsNotFound() {
        when(prescriptionRepository.findByConsultationId(CONSULTATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> prescriptionService.getByConsultation(CONSULTATION_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }

    // ---- listForPatient ----

    @Test
    void listForPatient_unknownPatientThrowsNotFound() {
        when(patientService.exists(PATIENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> prescriptionService.listForPatient(PATIENT_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));

        verifyNoInteractions(prescriptionRepository, prescriptionItemRepository);
    }

    @Test
    void listForPatient_withNoPrescriptionsReturnsAnEmptyListWithoutAMedicineQuery() {
        when(patientService.exists(PATIENT_ID)).thenReturn(true);
        when(prescriptionRepository.findTop50ByPatientIdOrderByCreatedAtDesc(PATIENT_ID)).thenReturn(List.of());

        assertThat(prescriptionService.listForPatient(PATIENT_ID)).isEmpty();

        verify(prescriptionItemRepository, never()).findByPrescriptionIdIn(anyCollection());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listForPatient_loadsAllMedicinesWithOneQueryAndGroupsThemPerPrescription() {
        UUID newerId = UUID.randomUUID();
        UUID olderId = UUID.randomUUID();
        when(patientService.exists(PATIENT_ID)).thenReturn(true);
        when(prescriptionRepository.findTop50ByPatientIdOrderByCreatedAtDesc(PATIENT_ID)).thenReturn(List.of(
                storedPrescription(newerId, UUID.randomUUID(), Instant.parse("2026-11-12T06:00:00Z")),
                storedPrescription(olderId, UUID.randomUUID(), Instant.parse("2026-11-12T05:00:00Z"))));
        when(prescriptionItemRepository.findByPrescriptionIdIn(anyCollection())).thenReturn(List.of(
                storedItem(olderId, 1, "Older second"),
                storedItem(newerId, 0, "Newer only"),
                storedItem(olderId, 0, "Older first")));

        List<PrescriptionResponse> result = prescriptionService.listForPatient(PATIENT_ID);

        assertThat(result).extracting(PrescriptionResponse::id).containsExactly(newerId, olderId);
        assertThat(result.get(0).items()).extracting(PrescriptionItemResponse::medicineName).containsExactly("Newer only");
        assertThat(result.get(1).items()).extracting(PrescriptionItemResponse::medicineName)
                .containsExactly("Older first", "Older second");

        ArgumentCaptor<Collection<UUID>> requestedIds = ArgumentCaptor.forClass(Collection.class);
        verify(prescriptionItemRepository).findByPrescriptionIdIn(requestedIds.capture());
        assertThat(requestedIds.getValue()).containsExactlyInAnyOrder(newerId, olderId);
    }
}
