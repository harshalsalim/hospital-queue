package com.hospitalqueue.doctor.entity;

/** Whether a session accepts new bookings. Matches the check constraint on slot.status. */
public enum SlotStatus {

    /** Open for booking while tokens remain. */
    AVAILABLE,

    /** Temporarily closed by the doctor; existing tokens stay, no new bookings. */
    PAUSED
}
