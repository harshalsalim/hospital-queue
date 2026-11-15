package com.hospitalqueue.patient.dto;

/** The result a payment gateway reports for a checkout. */
public enum PaymentOutcome {

    /** The money was received. */
    SUCCESS,

    /** The payment failed or was abandoned. */
    FAILED
}
