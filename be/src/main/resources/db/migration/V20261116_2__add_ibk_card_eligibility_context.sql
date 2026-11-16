-- QA-supplied issuer facts, distinct from YAMU registration. NULL means unverified.
ALTER TABLE user_card
    ADD COLUMN issuer_registered_on DATE NULL,
    ADD COLUMN ibk_context_month DATE NULL,
    ADD COLUMN ibk_salary_qualified BOOLEAN NULL,
    ADD COLUMN ibk_military_qualified BOOLEAN NULL,
    ADD CONSTRAINT ck_ibk_context_month CHECK (ibk_context_month IS NULL OR DAY(ibk_context_month)=1),
    ADD CONSTRAINT ck_ibk_context_fields CHECK (
        (ibk_context_month IS NULL AND ibk_salary_qualified IS NULL AND ibk_military_qualified IS NULL)
        OR ibk_context_month IS NOT NULL);
