-- ============================================================================
-- HospitalQueue MVP: initial schema (PostgreSQL 16, applied by Flyway)
-- Clinical free-text columns (notes, therapy_advice, lab_tests, medicine_name,
-- dosage) hold application-encrypted values stored as TEXT.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Lookup and tenant roots
-- ---------------------------------------------------------------------------

CREATE TABLE specialty (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE specialty IS 'Medical specialty lookup for doctors';

CREATE TABLE hospital (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    address     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE hospital IS 'Hospital (tenant root for multi-tenancy)';

CREATE TABLE plan (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name               VARCHAR(100) NOT NULL,
    monthly_fee_paise  INTEGER NOT NULL CHECK (monthly_fee_paise >= 0),
    features           JSONB NOT NULL DEFAULT '{}',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE plan IS 'Subscription plan definitions and feature flags';

-- ---------------------------------------------------------------------------
-- Patients and pharmacies (created before app_user, which links to them)
-- ---------------------------------------------------------------------------

CREATE TABLE patient (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_number  VARCHAR(20) NOT NULL UNIQUE,
    name          VARCHAR(255),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE patient IS 'Patient profile; global, not tied to one hospital. Phone stored in normalised format';

CREATE TABLE pharmacy (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id UUID REFERENCES hospital(id),
    name        VARCHAR(255) NOT NULL,
    is_in_house BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_pharmacy_in_house_hospital CHECK (NOT is_in_house OR hospital_id IS NOT NULL)
);
COMMENT ON TABLE pharmacy IS 'Pharmacy directory: in-house (hospital_id required) and independent pharmacies';

-- ---------------------------------------------------------------------------
-- Users and authentication
-- ---------------------------------------------------------------------------

CREATE TABLE app_user (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id   UUID REFERENCES hospital(id),
    pharmacy_id   UUID REFERENCES pharmacy(id),
    patient_id    UUID UNIQUE REFERENCES patient(id),
    email         VARCHAR(255) UNIQUE,
    password_hash VARCHAR(255),
    role          VARCHAR(32) NOT NULL
                  CHECK (role IN ('SYSTEM_ADMIN','HOSPITAL_ADMIN','DOCTOR','PATIENT','PHARMACY_STAFF')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_app_user_credentials CHECK (
        role = 'PATIENT' OR (email IS NOT NULL AND password_hash IS NOT NULL)
    ),
    CONSTRAINT ck_app_user_role_links CHECK (
        (role = 'SYSTEM_ADMIN'   AND hospital_id IS NULL     AND pharmacy_id IS NULL     AND patient_id IS NULL) OR
        (role = 'HOSPITAL_ADMIN' AND hospital_id IS NOT NULL AND pharmacy_id IS NULL     AND patient_id IS NULL) OR
        (role = 'DOCTOR'         AND hospital_id IS NOT NULL AND pharmacy_id IS NULL     AND patient_id IS NULL) OR
        (role = 'PATIENT'        AND hospital_id IS NULL     AND pharmacy_id IS NULL     AND patient_id IS NOT NULL) OR
        (role = 'PHARMACY_STAFF' AND hospital_id IS NULL     AND pharmacy_id IS NOT NULL AND patient_id IS NULL)
    )
);
COMMENT ON TABLE app_user IS 'Login account for every role. Patients log in by phone OTP (no email or password)';

-- ---------------------------------------------------------------------------
-- Doctors and sessions
-- ---------------------------------------------------------------------------

CREATE TABLE doctor (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id           UUID NOT NULL REFERENCES hospital(id),
    app_user_id           UUID UNIQUE REFERENCES app_user(id),
    specialty_id          UUID NOT NULL REFERENCES specialty(id),
    name                  VARCHAR(255) NOT NULL,
    registration_council  VARCHAR(100) NOT NULL,
    registration_number   VARCHAR(50) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_doctor_council_number UNIQUE (registration_council, registration_number)
);
COMMENT ON TABLE doctor IS 'Doctor profile with medical council registration';

CREATE TABLE slot (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id   UUID NOT NULL REFERENCES hospital(id),
    doctor_id     UUID NOT NULL REFERENCES doctor(id),
    date          DATE NOT NULL,
    start_time    TIME NOT NULL,
    end_time      TIME NOT NULL,
    max_tokens    INTEGER NOT NULL CHECK (max_tokens >= 0),
    booked_count  INTEGER NOT NULL DEFAULT 0 CHECK (booked_count >= 0),
    status        VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','PAUSED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_slot_times CHECK (end_time > start_time),
    CONSTRAINT ck_slot_booked_count CHECK (booked_count <= max_tokens),
    CONSTRAINT uq_slot_doctor_date_start UNIQUE (doctor_id, date, start_time)
);
COMMENT ON TABLE slot IS 'Doctor session with token capacity. Booking = atomic UPDATE of booked_count; the returned value is the token number';

-- ---------------------------------------------------------------------------
-- Tokens
-- ---------------------------------------------------------------------------

CREATE TABLE token (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id   UUID NOT NULL REFERENCES hospital(id),
    patient_id    UUID NOT NULL REFERENCES patient(id),
    slot_id       UUID NOT NULL REFERENCES slot(id),
    token_number  INTEGER NOT NULL CHECK (token_number > 0),
    status        VARCHAR(20) NOT NULL DEFAULT 'BOOKED'
                  CHECK (status IN ('BOOKED','WAITING','IN_CONSULTATION','COMPLETED','CANCELLED','NO_SHOW')),
    booked_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_token_slot_number UNIQUE (slot_id, token_number)
);
COMMENT ON TABLE token IS 'Queue token issued to a patient for a slot';

CREATE UNIQUE INDEX uq_token_patient_active_slot
    ON token (patient_id, slot_id)
    WHERE status IN ('BOOKED','WAITING','IN_CONSULTATION');

-- ---------------------------------------------------------------------------
-- Billing
-- ---------------------------------------------------------------------------

CREATE TABLE subscription (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id UUID NOT NULL REFERENCES hospital(id),
    plan_id     UUID NOT NULL REFERENCES plan(id),
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at     TIMESTAMPTZ NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','EXPIRED','CANCELLED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_subscription_dates CHECK (ends_at > started_at)
);
COMMENT ON TABLE subscription IS 'Hospital subscription history; only one ACTIVE row per hospital';

CREATE UNIQUE INDEX uq_subscription_hospital_active
    ON subscription (hospital_id)
    WHERE status = 'ACTIVE';

CREATE TABLE fee_ledger (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id        UUID NOT NULL REFERENCES hospital(id),
    patient_id         UUID NOT NULL REFERENCES patient(id),
    slot_id            UUID NOT NULL REFERENCES slot(id),
    token_id           UUID UNIQUE REFERENCES token(id),
    amount_paise       INTEGER NOT NULL CHECK (amount_paise >= 0),
    payment_status     VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                       CHECK (payment_status IN ('PENDING','SUCCESS','FAILED','REFUNDED')),
    gateway_reference  VARCHAR(255) NOT NULL UNIQUE,
    paid_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_fee_ledger_paid_at CHECK (payment_status <> 'SUCCESS' OR paid_at IS NOT NULL)
);
COMMENT ON TABLE fee_ledger IS 'Convenience fee per booking. Row exists before the token (payment first); token_id is filled after success. gateway_reference UNIQUE makes webhooks idempotent';

-- ---------------------------------------------------------------------------
-- Consultation and prescription
-- ---------------------------------------------------------------------------

CREATE TABLE consultation (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id  UUID NOT NULL REFERENCES hospital(id),
    patient_id   UUID NOT NULL REFERENCES patient(id),
    doctor_id    UUID NOT NULL REFERENCES doctor(id),
    token_id     UUID NOT NULL UNIQUE REFERENCES token(id),
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at     TIMESTAMPTZ,
    notes        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE consultation IS 'Doctor-patient consultation; notes are application-encrypted';

CREATE TABLE prescription (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id                  UUID NOT NULL REFERENCES hospital(id),
    patient_id                   UUID NOT NULL REFERENCES patient(id),
    doctor_id                    UUID NOT NULL REFERENCES doctor(id),
    consultation_id              UUID NOT NULL UNIQUE REFERENCES consultation(id),
    doctor_registration_council  VARCHAR(100) NOT NULL,
    doctor_registration_number   VARCHAR(50) NOT NULL,
    therapy_advice               TEXT,
    lab_tests                    TEXT,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE prescription IS 'Prescription with snapshot of doctor registration; free-text columns are application-encrypted';

CREATE TABLE prescription_item (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prescription_id  UUID NOT NULL REFERENCES prescription(id),
    medicine_name    TEXT NOT NULL,
    dosage           TEXT NOT NULL,
    duration_days    INTEGER NOT NULL CHECK (duration_days > 0),
    quantity         INTEGER NOT NULL CHECK (quantity > 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE prescription_item IS 'Medicine lines of a prescription; medicine_name and dosage are application-encrypted';

-- ---------------------------------------------------------------------------
-- Pharmacy fulfilment
-- ---------------------------------------------------------------------------

CREATE TABLE pharmacy_order (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id      UUID NOT NULL REFERENCES hospital(id),
    prescription_id  UUID NOT NULL REFERENCES prescription(id),
    pharmacy_id      UUID NOT NULL REFERENCES pharmacy(id),
    status           VARCHAR(20) NOT NULL DEFAULT 'SENT'
                     CHECK (status IN ('SENT','ACCEPTED','PARTIAL','REJECTED','PREPARING','READY','COLLECTED')),
    chosen_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE pharmacy_order IS 'Prescription sent to the pharmacy the patient chose (chosen_at records consent). Re-sending after REJECTED is allowed';

CREATE UNIQUE INDEX uq_pharmacy_order_prescription_active
    ON pharmacy_order (prescription_id)
    WHERE status <> 'REJECTED';

CREATE TABLE pharmacy_order_item (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pharmacy_order_id     UUID NOT NULL REFERENCES pharmacy_order(id),
    prescription_item_id  UUID NOT NULL REFERENCES prescription_item(id),
    quantity_requested    INTEGER NOT NULL CHECK (quantity_requested > 0),
    quantity_available    INTEGER,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_po_item_qty CHECK (quantity_available BETWEEN 0 AND quantity_requested),
    CONSTRAINT uq_po_item_order_item UNIQUE (pharmacy_order_id, prescription_item_id)
);
COMMENT ON TABLE pharmacy_order_item IS 'Per-medicine availability reported by the pharmacy';

-- ---------------------------------------------------------------------------
-- Audit
-- ---------------------------------------------------------------------------

CREATE TABLE audit_log (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id    UUID,
    user_id        UUID NOT NULL,
    user_role      VARCHAR(32) NOT NULL,
    patient_id     UUID NOT NULL,
    resource_type  VARCHAR(50) NOT NULL,
    resource_id    UUID NOT NULL,
    action         VARCHAR(10) NOT NULL CHECK (action IN ('READ','CREATE','UPDATE','SHARE','EXPORT')),
    reason         TEXT,
    accessed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE audit_log IS 'Append-only: INSERT and SELECT only; UPDATE and DELETE are revoked for the application database user. No foreign keys on purpose, so history survives deletions';

-- ---------------------------------------------------------------------------
-- Indexes for foreign keys not already covered by a UNIQUE constraint/index
-- ---------------------------------------------------------------------------

CREATE INDEX idx_pharmacy_hospital_id           ON pharmacy (hospital_id);

CREATE INDEX idx_app_user_hospital_id           ON app_user (hospital_id);
CREATE INDEX idx_app_user_pharmacy_id           ON app_user (pharmacy_id);

CREATE INDEX idx_doctor_hospital_id             ON doctor (hospital_id);
CREATE INDEX idx_doctor_specialty_id            ON doctor (specialty_id);

CREATE INDEX idx_slot_hospital_id               ON slot (hospital_id);

CREATE INDEX idx_token_hospital_id              ON token (hospital_id);
CREATE INDEX idx_token_patient_id               ON token (patient_id);

CREATE INDEX idx_subscription_hospital_id       ON subscription (hospital_id);
CREATE INDEX idx_subscription_plan_id           ON subscription (plan_id);

CREATE INDEX idx_fee_ledger_hospital_id         ON fee_ledger (hospital_id);
CREATE INDEX idx_fee_ledger_patient_id          ON fee_ledger (patient_id);
CREATE INDEX idx_fee_ledger_slot_id             ON fee_ledger (slot_id);

CREATE INDEX idx_consultation_hospital_id       ON consultation (hospital_id);
CREATE INDEX idx_consultation_patient_id        ON consultation (patient_id);
CREATE INDEX idx_consultation_doctor_id         ON consultation (doctor_id);

CREATE INDEX idx_prescription_hospital_id       ON prescription (hospital_id);
CREATE INDEX idx_prescription_patient_id        ON prescription (patient_id);
CREATE INDEX idx_prescription_doctor_id         ON prescription (doctor_id);

CREATE INDEX idx_prescription_item_prescription ON prescription_item (prescription_id);

CREATE INDEX idx_pharmacy_order_hospital_id     ON pharmacy_order (hospital_id);
CREATE INDEX idx_pharmacy_order_pharmacy_id     ON pharmacy_order (pharmacy_id, status);
CREATE INDEX idx_pharmacy_order_prescription_id ON pharmacy_order (prescription_id);

CREATE INDEX idx_po_item_prescription_item_id   ON pharmacy_order_item (prescription_item_id);

CREATE INDEX idx_audit_log_patient              ON audit_log (patient_id, accessed_at);
CREATE INDEX idx_audit_log_user                 ON audit_log (user_id, accessed_at);
CREATE INDEX idx_audit_log_resource             ON audit_log (resource_type, resource_id);
CREATE INDEX idx_audit_log_hospital_id          ON audit_log (hospital_id);