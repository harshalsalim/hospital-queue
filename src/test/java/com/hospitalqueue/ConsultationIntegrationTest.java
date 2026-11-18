package com.hospitalqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.hospitalqueue.consultation.service.ConsultationService;
import com.hospitalqueue.consultation.service.ConsultationService.StartResult;
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
 * Consultations against a REAL PostgreSQL (Docker must be running). The key proof: the doctor's notes are stored as
 * unreadable ciphertext in the table, yet read back as plain text through the application, including Malayalam text.
 * It also proves the doctor comes from the session and that simultaneous starts create one consultation.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConsultationIntegrationTest {

    private static final String NOTES = "Patient reports chest pain for two days. കഫക്കെട്ടും പനിയും ഉണ്ട്.";

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
    private JdbcClient jdbcClient;

    private record Session(UUID slotId, UUID doctorId, UUID hospitalId) {
    }

    private Session createSession() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        HospitalResponse hospital = hospitalService.create(
                new CreateHospitalRequest("Consultation Hospital " + suffix, "Test address " + suffix));
        SpecialtyResponse specialty = specialtyService.create(new CreateSpecialtyRequest("Consultation Specialty " + suffix));
        DoctorResponse doctor = doctorService.create(hospital.id(),
                new CreateDoctorRequest(specialty.id(), "Dr. Consult " + suffix, "Test Council", "CON-" + suffix));
        LocalDate date = LocalDate.now(ClockConfig.BUSINESS_ZONE).plusDays(1);
        SlotResponse slot = slotService.create(doctor.id(),
                new CreateSlotRequest(date, LocalTime.of(9, 0), LocalTime.of(12, 0), 5));
        return new Session(slot.id(), doctor.id(), hospital.id());
    }

    private TokenResponse bookToken(Session session) {
        long number = ThreadLocalRandom.current().nextLong(0, 900_000_000L);
        UUID patientId = patientService.register(new RegisterPatientRequest(String.format("9%09d", number), null)).id();
        return tokenService.book(patientId, session.slotId()).token();
    }

    /** Books a token and takes it through check-in and "call next", so the doctor can start the consultation. */
    private TokenResponse calledToken(Session session) {
        TokenResponse token = bookToken(session);
        queueService.checkIn(token.id());
        queueService.callNext(session.slotId());
        return token;
    }

    private String storedNotes(UUID consultationId) {
        return jdbcClient.sql("SELECT notes FROM consultation WHERE id = :id")
                .param("id", consultationId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private long consultationCount(UUID tokenId) {
        return jdbcClient.sql("SELECT count(*) FROM consultation WHERE token_id = :tokenId")
                .param("tokenId", tokenId)
                .query(Long.class)
                .single();
    }

    private static String codeOf(Throwable failure) {
        return failure instanceof ApiException apiException ? apiException.getCode() : failure.getClass().getSimpleName();
    }

    @Test
    void notesAreStoredEncryptedAndReadBackAsPlainText() {
        Session session = createSession();
        TokenResponse token = calledToken(session);

        StartResult started = consultationService.start(token.id(), NOTES);

        assertThat(started.created()).isTrue();
        String stored = storedNotes(started.consultation().id());
        assertThat(stored).startsWith("v1:");
        assertThat(stored).doesNotContain("chest pain").doesNotContain("കഫക്കെട്ടും");
        assertThat(consultationService.get(started.consultation().id()).notes()).isEqualTo(NOTES);
    }

    @Test
    void theConsultationTakesItsDoctorHospitalAndPatientFromTheTokenAndSession() {
        Session session = createSession();
        TokenResponse token = calledToken(session);

        ConsultationResponse consultation = consultationService.start(token.id(), null).consultation();

        assertThat(consultation.doctorId()).isEqualTo(session.doctorId());
        assertThat(consultation.hospitalId()).isEqualTo(session.hospitalId());
        assertThat(consultation.patientId()).isEqualTo(token.patientId());
        assertThat(consultation.tokenId()).isEqualTo(token.id());
        assertThat(consultation.startedAt()).isNotNull();
        assertThat(consultation.endedAt()).isNull();
        assertThat(consultation.notes()).isNull();
        assertThat(storedNotes(consultation.id())).isNull();
    }

    @Test
    void startingAgainReturnsTheSameConsultationAndNeverCreatesASecond() {
        Session session = createSession();
        TokenResponse token = calledToken(session);

        StartResult first = consultationService.start(token.id(), "first");
        StartResult second = consultationService.start(token.id(), "second");

        assertThat(second.created()).isFalse();
        assertThat(second.consultation().id()).isEqualTo(first.consultation().id());
        assertThat(second.consultation().notes()).isEqualTo("first");
        assertThat(consultationCount(token.id())).isEqualTo(1);
    }

    @Test
    void aPatientWhoWasNotCalledCannotHaveAConsultation() {
        Session session = createSession();
        TokenResponse token = bookToken(session);

        assertThatThrownBy(() -> consultationService.start(token.id(), "notes"))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("TOKEN_NOT_IN_CONSULTATION"));

        queueService.checkIn(token.id());
        assertThatThrownBy(() -> consultationService.start(token.id(), "notes"))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("TOKEN_NOT_IN_CONSULTATION"));

        assertThatThrownBy(() -> consultationService.start(UUID.randomUUID(), "notes"))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("NOT_FOUND"));
        assertThat(consultationCount(token.id())).isZero();
    }

    @Test
    void updatingTheNotesReEncryptsThemWithAFreshValueAndClearingStoresNothing() {
        Session session = createSession();
        TokenResponse token = calledToken(session);
        UUID id = consultationService.start(token.id(), "first notes").consultation().id();
        String firstStored = storedNotes(id);

        ConsultationResponse updated = consultationService.updateNotes(id, "second notes");

        assertThat(updated.notes()).isEqualTo("second notes");
        String secondStored = storedNotes(id);
        assertThat(secondStored).startsWith("v1:").isNotEqualTo(firstStored);
        assertThat(secondStored).doesNotContain("second");
        assertThat(consultationService.get(id).notes()).isEqualTo("second notes");

        assertThat(consultationService.updateNotes(id, "   ").notes()).isNull();
        assertThat(storedNotes(id)).isNull();
        assertThat(consultationService.get(id).notes()).isNull();

        assertThatThrownBy(() -> consultationService.updateNotes(UUID.randomUUID(), "x"))
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo("NOT_FOUND"));
    }

    @Test
    void simultaneousStartsCreateExactlyOneConsultation() throws Exception {
        Session session = createSession();
        TokenResponse token = calledToken(session);
        int attempts = 6;

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<StartResult>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return consultationService.start(token.id(), "notes");
            }));
        }
        go.countDown();

        Set<UUID> ids = new HashSet<>();
        long created = 0;
        for (Future<StartResult> future : futures) {
            StartResult result = future.get(60, TimeUnit.SECONDS);
            ids.add(result.consultation().id());
            if (result.created()) {
                created++;
            }
        }
        pool.shutdown();

        assertThat(ids).hasSize(1);
        assertThat(created).isEqualTo(1);
        assertThat(consultationCount(token.id())).isEqualTo(1);
    }
}
