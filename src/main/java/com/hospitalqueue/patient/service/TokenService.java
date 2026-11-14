package com.hospitalqueue.patient.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.dto.TokenAllocation;
import com.hospitalqueue.doctor.service.SlotBookingService;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.entity.Token;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.repository.TokenRepository;

/**
 * Token booking and lookup.
 *
 * <p>Booking is one database transaction: take the next token number from the session (atomic counter in the
 * doctor module) and store the token. If storing fails, the number goes back with the rollback, so numbers are
 * never skipped or reused.
 *
 * <p>Booking is also safe to repeat: when the patient already holds an active token in the session, that token
 * is returned instead of a second one. This holds even for two simultaneous identical requests, because the
 * database allows only one active token per patient and session.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    /**
     * Outcome of a booking request.
     *
     * @param token   the patient's token
     * @param created true when this request created the token, false when the patient already had it
     */
    public record BookingResult(TokenResponse token, boolean created) {
    }

    private final PatientService patientService;
    private final SlotBookingService slotBookingService;
    private final TokenRepository tokenRepository;
    private final TransactionTemplate transactionTemplate;

    public TokenService(PatientService patientService,
                        SlotBookingService slotBookingService,
                        TokenRepository tokenRepository,
                        TransactionTemplate transactionTemplate) {
        this.patientService = patientService;
        this.slotBookingService = slotBookingService;
        this.tokenRepository = tokenRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Books a token for a patient in a session, or returns the token the patient already holds there.
     *
     * @param patientId the patient
     * @param slotId    the doctor session
     * @return the token and whether it was newly created
     * @throws ApiException 404 unknown patient or session; 409 {@code SLOT_ENDED}, {@code SLOT_PAUSED},
     *                      {@code SLOT_FULL} or {@code SLOT_UNAVAILABLE} when the session cannot take a booking
     */
    public BookingResult book(UUID patientId, UUID slotId) {
        if (!patientService.exists(patientId)) {
            throw ApiException.notFound("Patient", patientId);
        }

        Optional<Token> existing = findActive(patientId, slotId);
        if (existing.isPresent()) {
            return new BookingResult(toResponse(existing.get()), false);
        }

        try {
            TokenResponse created = transactionTemplate.execute(status -> createToken(patientId, slotId));
            return new BookingResult(created, true);
        } catch (DataIntegrityViolationException duplicateOrOther) {
            // A simultaneous identical request won the race: its token is the answer.
            // Our transaction was rolled back, so the number we took was given back.
            return findActive(patientId, slotId)
                    .map(token -> new BookingResult(toResponse(token), false))
                    .orElseThrow(() -> duplicateOrOther);
        }
    }

    /**
     * Finds one token.
     *
     * @param id token id
     * @return the token
     * @throws ApiException 404 when no token has this id
     */
    @Transactional(readOnly = true)
    public TokenResponse get(UUID id) {
        return tokenRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("Token", id));
    }

    /**
     * Lists a patient's 50 most recent tokens, newest first.
     *
     * @param patientId the patient
     * @return the tokens, possibly empty
     * @throws ApiException 404 when the patient does not exist
     */
    @Transactional(readOnly = true)
    public List<TokenResponse> listForPatient(UUID patientId) {
        if (!patientService.exists(patientId)) {
            throw ApiException.notFound("Patient", patientId);
        }
        return tokenRepository.findTop50ByPatientIdOrderByBookedAtDesc(patientId).stream()
                .map(this::toResponse)
                .toList();
    }

    private TokenResponse createToken(UUID patientId, UUID slotId) {
        TokenAllocation allocation = slotBookingService.takeToken(slotId);
        Token saved = tokenRepository.saveAndFlush(
                new Token(allocation.hospitalId(), patientId, slotId, allocation.tokenNumber()));
        log.info("Token booked: tokenId={}, slotId={}, tokenNumber={}", saved.getId(), slotId, saved.getTokenNumber());
        return toResponse(saved);
    }

    private Optional<Token> findActive(UUID patientId, UUID slotId) {
        return tokenRepository.findFirstByPatientIdAndSlotIdAndStatusIn(patientId, slotId, TokenStatus.ACTIVE);
    }

    private TokenResponse toResponse(Token token) {
        return new TokenResponse(token.getId(), token.getHospitalId(), token.getPatientId(), token.getSlotId(),
                token.getTokenNumber(), token.getStatus(), token.getBookedAt());
    }
}
