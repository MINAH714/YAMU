-- S15P31E103-442. Registry only; no persona data on application startup.
CREATE TABLE qa_seed_dataset (
    dataset_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seed_version INT NOT NULL,
    base_date DATE NOT NULL COMMENT 'Korean fixture reference date',
    input_hash BINARY(32) NOT NULL COMMENT 'HMAC of canonical input; no plaintext credentials',
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (dataset_key)
) ENGINE=InnoDB;
CREATE TABLE qa_seed_member (
    dataset_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    persona_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    enriched BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (dataset_key, persona_key),
    UNIQUE KEY uk_qa_member_user (user_id),
    CONSTRAINT fk_qa_member_dataset FOREIGN KEY (dataset_key) REFERENCES qa_seed_dataset(dataset_key),
    CONSTRAINT fk_qa_member_user FOREIGN KEY (user_id) REFERENCES app_user(user_id),
    CONSTRAINT ck_qa_enriched CHECK (enriched IN (0,1))
) ENGINE=InnoDB;
