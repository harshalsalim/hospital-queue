package com.hospitalqueue.patient.repository;

import com.hospitalqueue.patient.entity.TokenStatus;

/**
 * Result row of the "tokens of a session grouped by status" query.
 *
 * @param status the token status
 * @param total  how many tokens of the session have that status
 */
public record TokenStatusCount(TokenStatus status, Long total) {
}
