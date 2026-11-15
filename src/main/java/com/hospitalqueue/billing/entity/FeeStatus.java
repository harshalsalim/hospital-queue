package com.hospitalqueue.billing.entity;

/** Payment state of a convenience fee. Matches the check constraint on fee_ledger.payment_status. */
public enum FeeStatus {

    /** Checkout started, payment not completed yet. */
    PENDING,

    /** Payment received. */
    SUCCESS,

    /** Payment failed or the checkout expired unpaid. */
    FAILED,

    /** Payment was received but no token could be issued, so the money is returned. */
    REFUNDED
}
