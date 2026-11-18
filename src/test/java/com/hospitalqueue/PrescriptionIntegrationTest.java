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
import org.springframework.jdbc.core.simple.JdbcClient;

import com.hospitalqueue.admin.dto.CreateHospitalRequest;
import com.hospitalqueue.admin.dto.CreateSpecialtyRequest;
import com.hospitalqueue.admin.dto.HospitalResponse;
import com.hospitalqueue.admin.dto.SpecialtyResponse;
import com.hospitalqueue.admin.service.HospitalService;
import com.hospitalqueue.admin.service.SpecialtyService;
import com.hospitalqueue.common.config.ClockConfig;
import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.consultation.dto.ConsultationResponse;
import com.hospitalqueue.consultation.dto.IssuePrescriptionRequest;
import com.hospitalqueue.consultation.dto.PrescriptionItemRequest;
import com.hospitalqueue.consultation.dto.PrescriptionItemResponse;
import com.hospitalqueue.consultation.dto.PrescriptionResponse;
import com.hospitalqueue.consultation.service.ConsultationService;
import com.hospitalqueue.consultation.service.PrescriptionService;
import com.hospitalqueue.doctor.dto.CreateDoctorRequest;
import com.hospitalqueue.doctor.dto.CreateSlotRequest;
import com.hospitalqueue.doctor.dto.DoctorResponse;
import com.hospitalqueue.doctor.dto.SlotResponse;
import com.hospitalqueue.doctor.service.DoctorService;
import com.hospitalqueue.doctor.service.SlotService;
import com.hospitalqueue.patient.dto.RegisterPatientRequest;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.service.PatientService;
import com.hospitalqueue.patient.service.QueueService;
import com.hospitalqueue.patient.service.TokenService;

/**
 * Prescriptions against a REAL PostgreSQL (Docker must be running). The key proofs: advice, lab tests, medicine names
 * and dosages are stored as unreadable ciphertext yet read back in full (Malayalam included); the medicines keep the
 * order the doctor wrote them; one consultation never gets two prescriptions, even when issued at the same moment;
 * a failed save never leaves a prescription without its medicines; and a patient's list is complete and newest first.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PrescriptionIntegrationTest {

    private static final String ADVICE = "Physiotherapy for ten days. ദിവസവും ധാരാളം വെള്ളം കുടിക്കുക.";
    private static final String LAB_TESTS = "CBC, ESR, fasting blood sugar";

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
    private ConsultationService consultationService;

    @Autowired
    private PrescriptionService prescriptionService;

    @Autowired
    private JdbcClient jdbcClient;

    private record Session(UUID slotId, DoctorResponse doctor) {
    }

    private Session createSession() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        HospitalResponse hospital = hospitalService.create(
                new CreateHospitalRequest("Prescription Hospital " + suffix, "Test address " + suffix));
        SpecialtyResponse specialty = specialtyService.create(new CreateSpecialtyRequest("Prescription Specialty " + suffix));
        DoctorResponse doctor = doctorService.create(hospital.id(),
                new CreateDoctorRequest(specialty.id(), "Dr. Rx " + suffix, "Test Council", "RX-" + suffix));
        LocalDate date = LocalDate.now(ClockConfig.BUSINESS_ZONE).plusDays(1);
        SlotResponse slot = slotService.create(doctor.id(),
                new CreateSlotRequest(date, LocalTime.of(9, 0), LocalTime.of(12, 0), 5));
        return new Session(slot.id(), doctor);
    }

    private UUID registerPatient() {
        long number = ThreadLocalRandom.current().nextLong(0, 900_000_000L);
        return patientService.register(new RegisterPatientRequest(String.format("9%09d", number), null)).id();
    }

    /** Books a token for the patient and takes it through check-in, "call next" and the start of the consultation. */
    private ConsultationResponse consultationFor(UUID patientId, Session session) {
        TokenResponse token = tokenService.book(patientId, session.slotId()).token();
        queueService.checkIn(token.id());
        queueService.callNext(session.slotId());
        return consultationService.start(token.id(), null).consultation();
    }

    private static PrescriptionItemRequest medicine(String name) {
        return new PrescriptionItemRequest(name, "1 tablet twice a day after food", 5, 10);
    }

    private long count(String sql, UUID id) {
        return jdbcClient.sql(sql).param("id", id).query(Long.class).single();
    }

    private static String codeOf(Throwable failure) {
        return failure instanceof ApiException apiException ? apiException.getCode() : failure.getClass().getSimpleName();
    }

    @Test
    void thePrescriptionIsStoredEncryptedAndReadBackInFull() {
        Session session = createSession();
        ConsultationResponse consultation = consultationFor(registerPatient(), session);

        PrescriptionResponse issued = prescriptionService.issue(consultation.id(), new IssuePrescriptionRequest(
                ADVICE, LAB_TESTS, List.of(medicine("Paracetamol 500mg"), medicine("Cetirizine 10mg"))));

        assertThat(issued.doctorId()).isEqualTo(session.doctor().id());
        assertThat(issued.doctorRegistrationCouncil()).isEqualTo(session.doctor().registrationCouncil());
        assertThat(issued.doctorRegistrationNumber()).isEqualTo(session.doctor().registrationNumber());
        assertThat(issued.patientId()).isEqualTo(consultation.patientId());
        assertThat(issued.consultationId()).isEqualTo(consultation.id());

        List<String> storedText = jdbcClient.sql("SELECT therapy_advice, lab_tests FROM prescription WHERE id = :id")
                .param("id", issued.id())
                .query((rs, row) -> rs.getString(1) + "|" + rs.getString(2))
                .list();
        assertThat(storedText).hasSize(1);
        assertThat(storedText.get(0)).contains("v1:").doesNotContain("Physiotherapy").doesNotContain("വെള്ളം").doesNotContain("ESR");

        List<String> storedItems = jdbcClient.sql("SELECT medicine_name, dosage FROM prescription_item WHERE prescription_id = :id")
                .param("id", issued.id())
                .query((rs, row) -> rs.getString(1) + "|" + rs.getString(2))
                .list();
        assertThat(storedItems).hasSize(2);
        assertThat(storedItems).allSatisfy(row -> assertThat(row)
                .startsWith("v1:").doesNotContain("Paracetamol").doesNotContain("Cetirizine").doesNotContain("tablet"));

        PrescriptionResponse read = prescriptionService.get(issued.id());
        assertThat(read.therapyAdvice()).isEqualTo(ADVICE);
        assertThat(read.labTests()).isEqualTo(LAB_TESTS);
        assertThat(read.items()).extracting(PrescriptionItemResponse::medicineName)
                .containsExactly("Paracetamol 500mg", "Cetirizine 10mg");
        assertThat(read.items().get(0).dosage()).isEqualTo("1 tablet twice a day after food");
        assertThat(prescriptionService.getByConsultation(consultation.id()).id()).isEqualTo(issued.id());
    }

    @Test
    void medicinesKeepTheOrderTheDoctorWroteThem() {
        Session session = createSession();
        ConsultationResponse consultation = consultationFor(registerPatient(), session);
        List<String> writtenOrder = List.of("Medicine 07", "Medicine 03", "Medicine 11", "Medicine 01", "Medicine 09",
                "Medicine 05", "Medicine 12", "Medicine 02", "Medicine 08", "Medicine 04", "Medicine 10", "Medicine 06");

        PrescriptionResponse issued = prescriptionService.issue(consultation.id(), new IssuePrescriptionRequest(
                null, null, writtenOrder.stream().map(PrescriptionIntegrationTest::medicine).toList()));

        assertThat(issued.items()).extracting(PrescriptionItemResponse::medicineName).containsExactlyElementsOf(writtenOrder);
        assertThat(prescriptionService.get(issued.id()).items()).extracting(PrescriptionItemResponse::medicineName)
                .containsExactlyElementsOf(writtenOrder);
    }

    @Test
    void aSecondPrescriptionForTheSameConsultationIsRefusedAndChangesNothing() {
        Session session = createSession();
        ConsultationResponse consultation = consultationFor(registerPatient(), session);
        prescriptionService.issue(consultation.id(), new IssuePrescriptionRequest(null, null, List.of(medicine("First"))));

        assertThatThrownBy(() -> prescriptionService.issue(consultation.id(),
                new IssuePrescriptionRequest("changed my mind", null, List.of(medicine("Second"), medicine("Third")))))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("PRESCRIPTION_EXISTS"));

        assertThat(count("SELECT count(*) FROM prescription WHERE consultation_id = :id", consultation.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM prescription_item i JOIN prescription p ON p.id = i.prescription_id "
                + "WHERE p.consultation_id = :id", consultation.id())).isEqualTo(1);
        assertThat(prescriptionService.getByConsultation(consultation.id()).items())
                .extracting(PrescriptionItemResponse::medicineName).containsExactly("First");
    }

    @Test
    void simultaneousIssuesCreateExactlyOnePrescriptionWithAllItsMedicinesAndNoOrphans() throws Exception {
        Session session = createSession();
        ConsultationResponse consultation = consultationFor(registerPatient(), session);
        int attempts = 6;

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    prescriptionService.issue(consultation.id(), new IssuePrescriptionRequest(
                            null, null, List.of(medicine("Medicine A"), medicine("Medicine B"))));
                    return "ISSUED";
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

        assertThat(outcomes.stream().filter("ISSUED"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> !"ISSUED".equals(outcome)))
                .hasSize(attempts - 1)
                .containsOnly("PRESCRIPTION_EXISTS");
        assertThat(count("SELECT count(*) FROM prescription WHERE consultation_id = :id", consultation.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM prescription_item i JOIN prescription p ON p.id = i.prescription_id "
                + "WHERE p.consultation_id = :id", consultation.id())).isEqualTo(2);
    }

    @Test
    void anEmptyPrescriptionAndUnknownIdsAreRefused() {
        Session session = createSession();
        ConsultationResponse consultation = consultationFor(registerPatient(), session);

        assertThatThrownBy(() -> prescriptionService.issue(consultation.id(), new IssuePrescriptionRequest("  ", null, List.of())))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("EMPTY_PRESCRIPTION"));
        assertThatThrownBy(() -> prescriptionService.getByConsultation(consultation.id()))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> prescriptionService.issue(UUID.randomUUID(), new IssuePrescriptionRequest("advice", null, null)))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> prescriptionService.get(UUID.randomUUID()))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> prescriptionService.listForPatient(UUID.randomUUID()))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("NOT_FOUND"));
    }

    @Test
    void aPatientSeesOnlyTheirOwnPrescriptionsNewestFirstEachWithItsMedicines() throws Exception {
        UUID patient = registerPatient();
        UUID otherPatient = registerPatient();
        Session firstSession = createSession();
        Session secondSession = createSession();

        PrescriptionResponse older = prescriptionService.issue(consultationFor(patient, firstSession).id(),
                new IssuePrescriptionRequest("older advice", null, List.of(medicine("Older A"), medicine("Older B"))));
        Thread.sleep(30);
        PrescriptionResponse newer = prescriptionService.issue(consultationFor(patient, secondSession).id(),
                new IssuePrescriptionRequest(null, "newer labs", List.of(medicine("Newer A"))));
        prescriptionService.issue(consultationFor(otherPatient, createSession()).id(),
                new IssuePrescriptionRequest("someone else's advice", null, List.of(medicine("Someone Else"))));

        List<PrescriptionResponse> list = prescriptionService.listForPatient(patient);

        assertThat(list).extracting(PrescriptionResponse::id).containsExactly(newer.id(), older.id());
        assertThat(list.get(0).items()).extracting(PrescriptionItemResponse::medicineName).containsExactly("Newer A");
        assertThat(list.get(1).items()).extracting(PrescriptionItemResponse::medicineName)
                .containsExactly("Older A", "Older B");
        assertThat(list).allSatisfy(item -> assertThat(item.patientId()).isEqualTo(patient));
        assertThat(prescriptionService.listForPatient(registerPatient())).isEmpty();
    }
}
