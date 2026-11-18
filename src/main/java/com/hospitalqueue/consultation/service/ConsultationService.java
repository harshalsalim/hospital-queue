package com.hospitalqueue.consultation.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.consultation.dto.ConsultationResponse;
import com.hospitalqueue.consultation.entity.Consultation;
import com.hospitalqueue.consultation.repository.ConsultationRepository;
import com.hospitalqueue.doctor.dto.SlotResponse;
import com.hospitalqueue.doctor.service.SlotService;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.service.TokenService;

/**
 * Business logic for consultations: the record of a doctor seeing a patient.
 *
 * <p>A consultation starts for a token the doctor has called (status IN_CONSULTATION). The doctor is taken from the
 * token's session, so it is correct by construction. A token has at most one consultation, enforced by the database,
 * and starting it again returns the existing one (safe to retry).
 *
 * <p>The notes are encrypted at rest (see the entity). Nothing here caches consultations or writes notes to a log.
 */
@Service
public class ConsultationService {

    /**
     * Outcome of starting a consultation.
     *
     * @param consultation the consultation
     * @param created      true when this request created it, false when the token already had one
     */
    public record StartResult(ConsultationResponse consultation, boolean created) {
    }

    private final ConsultationRepository consultationRepository;
    private final TokenService tokenService;
    private final SlotService slotService;

    public ConsultationService(ConsultationRepository consultationRepository,
                               TokenService tokenService,
                               SlotService slotService) {
        this.consultationRepository = consultationRepository;
        this.tokenService = tokenService;
        this.slotService = slotService;
    }

    /**
     * Starts the consultation of a called patient, or returns the one that already exists for the token.
     *
     * @param tokenId the patient's token
     * @param notes   optional first notes; blank counts as none
     * @return the consultation and whether it was newly created
     * @throws ApiException 404 unknown token; 409 {@code TOKEN_NOT_IN_CONSULTATION} when the patient has not been called
     */
    public StartResult start(UUID tokenId, String notes) {
        Optional<Consultation> existing = consultationRepository.findByTokenId(tokenId);
        if (existing.isPresent()) {
            return new StartResult(toResponse(existing.get()), false);
        }

        TokenResponse token = tokenService.get(tokenId);
        if (token.status() != TokenStatus.IN_CONSULTATION) {
            throw ApiException.conflict("TOKEN_NOT_IN_CONSULTATION",
                    "A consultation can only start for a patient who has been called");
        }
        SlotResponse slot = slotService.get(token.slotId());

        try {
            Consultation saved = consultationRepository.saveAndFlush(new Consultation(
                    token.hospitalId(), token.patientId(), slot.doctorId(), tokenId, blankToNull(notes)));
            return new StartResult(toResponse(saved), true);
        } catch (DataIntegrityViolationException lostTheRace) {
            // A simultaneous request started it first: that consultation is the answer.
            return consultationRepository.findByTokenId(tokenId)
                    .map(winner -> new StartResult(toResponse(winner), false))
                    .orElseThrow(() -> lostTheRace);
        }
    }

    /**
     * Finds one consultation.
     *
     * @param id consultation id
     * @return the consultation, notes decrypted
     * @throws ApiException 404 when no consultation has this id
     */
    @Transactional(readOnly = true)
    public ConsultationResponse get(UUID id) {
        return toResponse(find(id));
    }

    /**
     * Replaces the doctor's notes.
     *
     * @param id    consultation id
     * @param notes the new notes; null or blank clears them
     * @return the updated consultation
     * @throws ApiException 404 when no consultation has this id
     */
    @Transactional
    public ConsultationResponse updateNotes(UUID id, String notes) {
        Consultation consultation = find(id);
        consultation.changeNotes(blankToNull(notes));
        return toResponse(consultationRepository.saveAndFlush(consultation));
    }

    private Consultation find(UUID id) {
        return consultationRepository.findById(id).orElseThrow(() -> ApiException.notFound("Consultation", id));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ConsultationResponse toResponse(Consultation consultation) {
        return new ConsultationResponse(consultation.getId(), consultation.getHospitalId(), consultation.getPatientId(),
                consultation.getDoctorId(), consultation.getTokenId(), consultation.getStartedAt(),
                consultation.getEndedAt(), consultation.getNotes());
    }
}
