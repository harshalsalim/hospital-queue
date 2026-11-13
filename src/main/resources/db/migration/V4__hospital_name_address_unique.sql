-- A hospital is identified by its name AND address, ignoring upper/lower case. Two hospitals may share
-- a name (different branches), but the same name at the same address is a duplicate.
-- The application checks first for a friendly 409; this index is the guarantee under concurrent requests.
-- The index uses an md5 hash of the pair because addresses can be long and written in Malayalam,
-- and a plain index on long multi-byte text can exceed PostgreSQL's index row size limit.
CREATE UNIQUE INDEX uq_hospital_name_address
    ON hospital (md5(lower(name) || '|' || lower(address)));
