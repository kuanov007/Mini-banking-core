-- Idempotency: a money-moving request carries an Idempotency-Key; the operation it created keeps the key and a
-- SHA-256 of the normalized request, so a retry with the same key is replayed instead of moving money twice.
-- Operations created before this migration keep NULL in both columns.
alter table operation
    add column idempotency_key varchar(64),
    add column request_hash    char(64);

alter table operation
    -- keys are per initiator, so two users can't collide; NULLs are distinct, so old rows don't clash.
    -- Its index also serves the lookup by (initiated_by, idempotency_key)
    add constraint uk_operation_initiator_idempotency_key unique (initiated_by, idempotency_key),
    add constraint ck_operation_idempotency_key_with_hash check ((idempotency_key is null) = (request_hash is null)),
    add constraint ck_operation_request_hash_format check (request_hash ~ '^[0-9a-f]{64}$');
