ALTER TABLE qa_seed_member ADD UNIQUE KEY uk_qa_dataset_user (dataset_key,user_id);
CREATE TABLE account_history_stream (
 stream_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 linked_account_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 source VARCHAR(20) NOT NULL, dataset_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 generation BIGINT NOT NULL DEFAULT 0, active BOOLEAN NOT NULL DEFAULT FALSE,
 range_from DATE NULL, range_to DATE NULL, input_hash VARCHAR(64) CHARACTER SET ascii NULL,
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_history_source (linked_account_id, source),
 CONSTRAINT fk_history_account FOREIGN KEY (linked_account_id,user_id) REFERENCES linked_account(linked_account_id,user_id),
 CONSTRAINT fk_history_dataset FOREIGN KEY (dataset_key,user_id) REFERENCES qa_seed_member(dataset_key,user_id),
 CONSTRAINT ck_history_source CHECK ((source='SSAFY' AND dataset_key IS NULL) OR (source='QA_SYNTHETIC' AND dataset_key IS NOT NULL)),
 CONSTRAINT ck_history_generation CHECK (generation>=0 AND version>=0),
 CONSTRAINT ck_history_active CHECK (active IN (0,1)),
 CONSTRAINT ck_history_qa_range CHECK (source<>'QA_SYNTHETIC' OR (range_from IS NOT NULL AND range_to IS NOT NULL AND input_hash IS NOT NULL)),
 CONSTRAINT ck_history_range CHECK ((range_from IS NULL AND range_to IS NULL) OR (range_from IS NOT NULL AND range_to IS NOT NULL AND range_from<=range_to))
) ENGINE=InnoDB;

CREATE TABLE account_transaction (
 transaction_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 stream_id BIGINT NOT NULL,
 external_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 occurred_at DATETIME(6) NOT NULL, direction VARCHAR(12) NOT NULL,
 amount BIGINT NOT NULL, balance_after BIGINT NOT NULL,
 content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_history_external (stream_id,external_id),
 UNIQUE KEY uk_transaction_owner (transaction_id,stream_id),
 KEY ix_history_time (stream_id,occurred_at),
 CONSTRAINT fk_transaction_stream FOREIGN KEY (stream_id) REFERENCES account_history_stream(stream_id),
 CONSTRAINT ck_history_amount CHECK (amount>=0),
 CONSTRAINT ck_history_direction CHECK (direction IN ('DEPOSIT','WITHDRAWAL'))
) ENGINE=InnoDB;

CREATE TABLE account_history_sync_run (
 run_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, stream_id BIGINT NOT NULL,
 generation BIGINT NOT NULL, range_from DATE NOT NULL, range_to DATE NOT NULL,
 status VARCHAR(40) NOT NULL, reported_count BIGINT NULL, received_count BIGINT NULL,
 queried_at DATETIME(6) NULL, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_sync_owner (run_id,stream_id),
 CONSTRAINT fk_sync_stream FOREIGN KEY (stream_id) REFERENCES account_history_stream(stream_id),
 CONSTRAINT ck_sync_range CHECK (range_from<=range_to),
 KEY ix_sync_stream (stream_id,run_id)
) ENGINE=InnoDB;

CREATE TABLE account_history_coverage (
 coverage_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, stream_id BIGINT NOT NULL,
 run_id BIGINT NOT NULL, range_from DATE NOT NULL, range_to DATE NOT NULL,
 valid BOOLEAN NOT NULL, opening_balance BIGINT NULL,
 opening_evidence VARCHAR(24) NOT NULL,
 opening_transaction_id BIGINT NULL, opening_coverage_id BIGINT NULL,
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_coverage_run (run_id),
 UNIQUE KEY uk_coverage_owner (coverage_id,stream_id),
 KEY ix_coverage_range (stream_id,range_from,range_to),
 CONSTRAINT fk_coverage_stream FOREIGN KEY (stream_id) REFERENCES account_history_stream(stream_id),
 CONSTRAINT fk_coverage_run FOREIGN KEY (run_id,stream_id) REFERENCES account_history_sync_run(run_id,stream_id),
 CONSTRAINT fk_opening_transaction FOREIGN KEY (opening_transaction_id,stream_id) REFERENCES account_transaction(transaction_id,stream_id),
 CONSTRAINT fk_opening_coverage FOREIGN KEY (opening_coverage_id,stream_id) REFERENCES account_history_coverage(coverage_id,stream_id),
 CONSTRAINT ck_opening_evidence CHECK (
   (opening_evidence='NONE' AND opening_balance IS NULL AND opening_transaction_id IS NULL AND opening_coverage_id IS NULL) OR
   (opening_evidence='FIRST_TRANSACTION' AND opening_balance IS NOT NULL AND opening_transaction_id IS NOT NULL AND opening_coverage_id IS NULL) OR
   (opening_evidence='PREVIOUS_COVERAGE' AND opening_balance IS NOT NULL AND opening_transaction_id IS NULL AND opening_coverage_id IS NOT NULL) OR
   (opening_evidence='QA_SCENARIO' AND opening_balance IS NOT NULL AND opening_transaction_id IS NULL AND opening_coverage_id IS NULL)),
 CONSTRAINT ck_coverage_valid CHECK (valid IN (0,1)),
 CONSTRAINT ck_coverage_range CHECK (range_from<=range_to)
) ENGINE=InnoDB;
