package com.hospitalqueue.patient.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.hospitalqueue.common.config.CacheNames;
import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.service.SlotService;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.repository.TokenQueueRepository;
import com.hospitalqueue.patient.repository.TokenRepository;

/**
 * The doctor's queue: patients arrive (check-in), the doctor calls the next patient, finishes the consultation, or
 * marks a patient as not present.
 *
 * <p>Every change is one atomic conditional UPDATE, so two devices acting on the same token cannot both succeed, and
 * every operation is safe to repeat: asking for a state the token is already in returns the token unchanged, while
 * a transition that makes no sense (for example completing a token that was never called) is refused with
 * {@code INVALID_TOKEN_STATE}.
 *
 * <p>Every successful change evicts the session's cached queue snapshot (after the database change is committed),
 * so the live queue views show it at once.
 *
 * <p>This service lives in the patient module because tokens belong to it, and the doctor module must not depend on
 * the patient module. Its endpoints serve the doctor's dashboard.
 */
@Service
public class QueueService {

    private final TokenService tokenService;
    private final TokenRepository tokenRepository;
    private final TokenQueueRepository tokenQueueRepository;
    private final SlotService slotService;
    private final TransactionTemplate transactionTemplate;

    public QueueService(TokenService tokenService,
                        TokenRepository tokenRepository,
                        TokenQueueRepository tokenQueueRepository,
                        SlotService slotService,
                        TransactionTemplate transactionTemplate) {
        this.tokenService = tokenService;
        this.tokenRepository = tokenRepository;
        this.tokenQueueRepository = tokenQueueRepository;
        this.slotService = slotService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * The patient has arrived: BOOKED becomes WAITING.
     *
     * @param tokenId the token
     * @return the token (already WAITING is fine and changes nothing)
     * @throws ApiException 404 unknown token; 409 {@code INVALID_TOKEN_STATE} when the token is past this step
     */
    @CacheEvict(cacheNames = CacheNames.QUEUE, key = "'slot:' + #result.slotId()")
    @Transactional
    public TokenResponse checkIn(UUID tokenId) {
        int changed = tokenRepository.checkIn(tokenId);
        return afterTransition(tokenId, changed, TokenStatus.WAITING, "checked in");
    }

    /**
     * Calls the next patient: the WAITING token with the lowest number becomes IN_CONSULTATION. Patients who have not
     * checked in are skipped; they are called when they arrive.
     *
     * @param slotId the session
     * @return the token now in consultation
     * @throws ApiException 404 unknown session; 409 {@code CONSULTATION_IN_PROGRESS} when the previous patient has not
     *                      been completed yet (also when two calls race), {@code QUEUE_EMPTY} when nobody is waiting
     */
    @CacheEvict(cacheNames = CacheNames.QUEUE, key = "'slot:' + #result.slotId()")
    public TokenResponse callNext(UUID slotId) {
        if (!slotService.exists(slotId)) {
            throw ApiException.notFound("Slot", slotId);
        }
        if (tokenRepository.existsBySlotIdAndStatus(slotId, TokenStatus.IN_CONSULTATION)) {
            throw consultationInProgress();
        }

        UUID calledId;
        try {
            Optional<UUID> called = transactionTemplate.execute(status -> tokenQueueRepository.startNext(slotId));
            calledId = called == null ? null : called.orElse(null);
        } catch (DataIntegrityViolationException anotherCallWonTheRace) {
            throw consultationInProgress();
        }
        if (calledId == null) {
            throw ApiException.conflict("QUEUE_EMPTY", "No patient is waiting");
        }
        return tokenService.get(calledId);
    }

    /**
     * The consultation is finished: IN_CONSULTATION becomes COMPLETED.
     *
     * @param tokenId the token
     * @return the token (already COMPLETED is fine and changes nothing)
     * @throws ApiException 404 unknown token; 409 {@code INVALID_TOKEN_STATE} when the patient was never called
     */
    @CacheEvict(cacheNames = CacheNames.QUEUE, key = "'slot:' + #result.slotId()")
    @Transactional
    public TokenResponse complete(UUID tokenId) {
        int changed = tokenRepository.complete(tokenId);
        return afterTransition(tokenId, changed, TokenStatus.COMPLETED, "completed");
    }

    /**
     * The patient did not come: BOOKED, WAITING or IN_CONSULTATION becomes NO_SHOW.
     *
     * @param tokenId the token
     * @return the token (already NO_SHOW is fine and changes nothing)
     * @throws ApiException 404 unknown token; 409 {@code INVALID_TOKEN_STATE} when the token is already finished
     */
    @CacheEvict(cacheNames = CacheNames.QUEUE, key = "'slot:' + #result.slotId()")
    @Transactional
    public TokenResponse markNoShow(UUID tokenId) {
        int changed = tokenRepository.markNoShow(tokenId);
        return afterTransition(tokenId, changed, TokenStatus.NO_SHOW, "marked as no-show");
    }

    /**
     * Reads the token after an attempted change. Success when this call changed it, or when it was already in the
     * wanted state (a repeated request); otherwise the requested step does not fit the token's state.
     */
    private TokenResponse afterTransition(UUID tokenId, int changed, TokenStatus target, String action) {
        TokenResponse token = tokenService.get(tokenId);
        if (changed == 1 || token.status() == target) {
            return token;
        }
        throw ApiException.conflict("INVALID_TOKEN_STATE", "A token that is " + token.status() + " cannot be " + action);
    }

    private static ApiException consultationInProgress() {
        return ApiException.conflict("CONSULTATION_IN_PROGRESS",
                "Finish the current consultation before calling the next patient");
    }
}
