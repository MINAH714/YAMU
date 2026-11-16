-- QA-only mapping: the synthetic closing balance is materialized by a real funding transaction.
-- Neither side's transactions/balances are rewritten or summed with the other side.
CREATE TABLE qa_seed_funding_boundary (
    qa_stream_id BIGINT NOT NULL PRIMARY KEY,
    actual_stream_id BIGINT NOT NULL,
    funding_transaction_id BIGINT NOT NULL,
    funding_content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    funded_balance BIGINT NOT NULL,
    CONSTRAINT fk_qa_funding_stream FOREIGN KEY (qa_stream_id) REFERENCES account_history_stream(stream_id),
    CONSTRAINT fk_qa_funding_transaction FOREIGN KEY (funding_transaction_id,actual_stream_id)
        REFERENCES account_transaction(transaction_id,stream_id),
    CONSTRAINT ck_qa_funded_balance CHECK (funded_balance > 0)
) ENGINE=InnoDB;
