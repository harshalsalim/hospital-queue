-- Specialty names must be unique ignoring case ("Cardiology" and "cardiology" are the same).
-- The application checks this first for a friendly 409; this index guarantees it even
-- when two requests arrive at the same time.
CREATE UNIQUE INDEX uq_specialty_name_lower ON specialty (lower(name));
