package com.hospitalqueue.patient.entity;

import java.util.EnumSet;
import java.util.Set;

/** Lifecycle of a token. Matches the check constraint on token.status. */
public enum TokenStatus {

    /** Booked, the patient has not arrived yet. */
    BOOKED,

    /** The patient has arrived and is waiting in the queue. */
    WAITING,

    /** The doctor is seeing the patient now. */
    IN_CONSULTATION,

    /** The consultation is finished. */
    COMPLETED,

    /** The patient or the hospital cancelled the token. */
    CANCELLED,

    /** The patient did not come. */
    NO_SHOW;

    /** Statuses in which a token still counts: a patient can hold only one of these per session. */
    public static final Set<TokenStatus> ACTIVE = EnumSet.of(BOOKED, WAITING, IN_CONSULTATION);
}
