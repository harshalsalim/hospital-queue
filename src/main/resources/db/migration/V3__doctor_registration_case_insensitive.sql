-- A doctor's medical council registration (council + number) must be unique ignoring case and
-- must be impossible to duplicate even when two requests arrive at the same time.
-- The application checks first for a friendly 409; this index is the guarantee.
CREATE UNIQUE INDEX uq_doctor_registration_lower
    ON doctor (lower(registration_council), lower(registration_number));
