package com.hospitalqueue.patient.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospitalqueue.patient.dto.QueueStatusResponse;
import com.hospitalqueue.patient.dto.TokenPositionResponse;
import com.hospitalqueue.patient.service.QueueStatusService;

/**
 * Live queue views for the waiting-room display and for patients' phones. The data comes from a Redis cache of a few
 * seconds, so these endpoints are cheap to poll. TEMPORARY: open until the authentication stage, when a patient will be
 * able to see only their own position.
 */
@RestController
@RequestMapping("/api/slots")
public class QueueStatusController {

    private final QueueStatusService queueStatusService;

    public QueueStatusController(QueueStatusService queueStatusService) {
        this.queueStatusService = queueStatusService;
    }

    /** The live queue of a session: who is being served, who is next, and the counts. 404 for an unknown session. */
    @GetMapping("/{slotId}/queue")
    public QueueStatusResponse getSlotQueue(@PathVariable UUID slotId) {
        return queueStatusService.getSlotQueue(slotId);
    }

    /** One patient's place in the queue, with an estimated wait. 404 for an unknown session or token. */
    @GetMapping("/{slotId}/queue/tokens/{tokenId}")
    public TokenPositionResponse getTokenPosition(@PathVariable UUID slotId, @PathVariable UUID tokenId) {
        return queueStatusService.getTokenPosition(slotId, tokenId);
    }
}
