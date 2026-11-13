-- A doctor can never have two overlapping sessions on the same day, even when two requests arrive
-- at the same time. The application checks first for a friendly 409 (SLOT_OVERLAP); this exclusion
-- constraint is the guarantee. Sessions are half-open ranges [start, end), so 09:00-12:00 and
-- 12:00-15:00 do not overlap.
-- btree_gist lets a GiST index compare doctor_id with "=" together with a range overlap.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE slot
    ADD CONSTRAINT ex_slot_no_overlap
    EXCLUDE USING gist (
        doctor_id WITH =,
        tsrange(("date" + start_time), ("date" + end_time), '[)') WITH &&
    );
