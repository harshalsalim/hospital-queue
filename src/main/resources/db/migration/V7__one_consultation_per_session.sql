-- A doctor sees one patient at a time: a session can have at most ONE token IN_CONSULTATION.
-- "Call next" checks this first for a friendly 409 (CONSULTATION_IN_PROGRESS); this index guarantees it even when
-- two "call next" requests arrive at the same moment, for example from two devices.
CREATE UNIQUE INDEX uq_token_one_in_consultation_per_slot
    ON token (slot_id)
    WHERE status = 'IN_CONSULTATION';

-- Supports the "next waiting token of a session, lowest number first" query used by "call next".
CREATE INDEX idx_token_slot_status_number
    ON token (slot_id, status, token_number);
