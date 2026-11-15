package com.hospitalqueue.patient.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.service.QueueService;

/**
 * Queue endpoints for the doctor's dashboard and the reception desk. TEMPORARY: open until the authentication stage,
 * when check-in will be allowed for reception staff, and calling, completing and no-show only for the doctor of
 * that session.
 *
 * <p>All endpoints are safe to repeat: asking for a state the token is already in returns it unchanged.
 */
@RestController
@RequestMapping("/api/queue")
public class QueueController {

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    /** The patient has arrived (BOOKED to WAITING). 404 unknown token; 409 when the token is past this step. */
    @PostMapping("/tokens/{tokenId}/check-in")
    public TokenResponse checkIn(@PathVariable UUID tokenId) {
        return queueService.checkIn(tokenId);
    }

    /**
     * Calls the next waiting patient (lowest token number first). 404 unknown session; 409 when the current consultation
     * is not completed yet or nobody is waiting.
     */
    @PostMapping("/slots/{slotId}/next")
    public TokenResponse callNext(@PathVariable UUID slotId) {
        return queueService.callNext(slotId);
    }

    /** Finishes the consultation (IN_CONSULTATION to COMPLETED). 404 unknown token; 409 when the patient was never called. */
    @PostMapping("/tokens/{tokenId}/complete")
    public TokenResponse complete(@PathVariable UUID tokenId) {
        return queueService.complete(tokenId);
    }

    /** The patient did not come (to NO_SHOW). 404 unknown token; 409 when the token is already finished. */
    @PostMapping("/tokens/{tokenId}/no-show")
    public TokenResponse markNoShow(@PathVariable UUID tokenId) {
        return queueService.markNoShow(tokenId);
    }
}
