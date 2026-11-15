-- A patient can have at most ONE open (PENDING) checkout per session. Starting a checkout twice, even from two
-- devices at the same moment, therefore never creates two payments for the same token.
-- Checkouts that were never paid are closed (marked FAILED) when their slot hold has expired.
CREATE UNIQUE INDEX uq_fee_ledger_pending_patient_slot
    ON fee_ledger (patient_id, slot_id)
    WHERE payment_status = 'PENDING';
