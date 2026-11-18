-- Medicines are shown in the order the doctor wrote them. Without an explicit order column, PostgreSQL may return the
-- rows of a prescription in any order. (The table holds no data yet, so the default only matters for old rows.)
ALTER TABLE prescription_item
    ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
