-- Keep V20261026_1 unchanged: existing databases retain its Flyway checksum.
-- RENAME preserves existing usage amounts, restoration markers, indexes and FKs.
RENAME TABLE payment_limit_impact TO payment_limit_usage;

ALTER TABLE payment_limit_usage
    RENAME COLUMN payment_limit_impact_id TO payment_limit_usage_id,
    DROP CHECK ck_payment_limit_impact_version,
    ADD CONSTRAINT ck_payment_limit_usage_version CHECK (version >= 0);
