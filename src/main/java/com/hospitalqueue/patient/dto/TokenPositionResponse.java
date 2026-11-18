package com.hospitalqueue.patient.dto;

import java.time.Instant;
import java.util.UUID;

import com.hospitalqueue.patient.entity.TokenStatus;

/**
 * Where one patient stands in the queue.
 *
 * @param tokenId                token id
 * @param tokenNumber            the patient's token number
 * @param status                 the token's status
 * @param tokensAhead            tokens with a lower number that are still in the queue (0 for a finished token)
 * @param estimatedWaitMinutes   estimated wait: tokens ahead times the average consultation length; 0 when being
 *                               served; null for a finished token. It is an estimate: tokens ahead whose patients
 *                               have not arrived count too, so the real wait is often shorter.
 * @param nowServingTokenNumber  the token being served now, or null when nobody is in consultation
 * @param asOf                   when this was read; it can be a few seconds old
 */
public record TokenPositionResponse(
        UUID tokenId,
        int tokenNumber,
        TokenStatus status,
        int tokensAhead,
        Integer estimatedWaitMinutes,
        Integer nowServingTokenNumber,
        Instant asOf) {
}
