-- Approved Epic 7 storage changes. Existing migration files remain unchanged.
-- cashflow_item holds the rule/defaults; cashflow_occurrence holds dated plans,
-- NOT actual withdrawals. Forecast/simulation result time series are not stored.
ALTER TABLE payment_transaction
    ADD COLUMN spending_class VARCHAR(16) NULL COMMENT 'LIVING / LARGE / EXCLUDED; 미분류 NULL, 실적 인정액과 별개',
    ADD COLUMN spending_class_source VARCHAR(16) NULL COMMENT 'AUTO / USER; 미분류 NULL, USER 수정은 재분류로 덮어쓰지 않음',
    ADD COLUMN memo VARCHAR(100) NULL COMMENT '사용자 결제 메모; 민감정보 입력 금지',
    ADD UNIQUE KEY uk_payment_owner (payment_transaction_id, user_id),
    ADD CONSTRAINT ck_payment_spending_class CHECK (spending_class IS NULL OR spending_class IN ('LIVING','LARGE','EXCLUDED')),
    ADD CONSTRAINT ck_payment_spending_pair CHECK (
        (spending_class IS NULL AND spending_class_source IS NULL)
        OR (spending_class IS NOT NULL AND spending_class_source IS NOT NULL AND spending_class_source IN ('AUTO','USER')));

-- Drop before MODIFY: the old CHECK mentions the column being changed.
ALTER TABLE cashflow_item DROP CHECK ck_cashflow_amount;
ALTER TABLE cashflow_item
    MODIFY COLUMN amount BIGINT NULL COMMENT '발생 건 생성 시 기본 예정 금액; NULL 금액 미정, 0원과 구분',
    MODIFY COLUMN scheduled_date DATE NOT NULL COMMENT '한국 기준 첫 발생일; created_at과 별개',
    ADD COLUMN user_card_id BIGINT NULL COMMENT '예정 결제 카드; 같은 사용자 소유',
    ADD COLUMN category_code VARCHAR(64) NULL COMMENT '사용자가 지정한 업종 코드; 분류 정책은 POL-004',
    ADD COLUMN item_kind VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED' COMMENT 'SCHEDULED / PURCHASE_PLAN; 기존 행은 일반 예정 항목',
    ADD COLUMN payment_plan VARCHAR(16) NULL COMMENT 'LUMP_SUM / INSTALLMENT; 카드 결제 방식',
    ADD COLUMN installment_months SMALLINT NULL COMMENT '일시불 1, 할부 총 개월 수; 비카드 항목 NULL',
    ADD COLUMN purchase_state VARCHAR(16) NULL COMMENT '구매 계획만 ACTIVE / FULFILLED / CANCELED',
    ADD COLUMN previous_cashflow_item_id BIGINT NULL COMMENT '이후 일정 변경 시 이전 규칙; 기존 규칙과 이력은 보존',
    ADD UNIQUE KEY uk_cashflow_owner (cashflow_item_id, user_id),
    ADD KEY ix_cashflow_card_owner (user_card_id, user_id),
    ADD KEY ix_cashflow_previous_owner (previous_cashflow_item_id, user_id),
    ADD CONSTRAINT fk_cashflow_card_owner FOREIGN KEY (user_card_id, user_id) REFERENCES user_card (user_card_id, user_id),
    ADD CONSTRAINT ck_cashflow_amount CHECK (amount IS NULL OR amount > 0),
    ADD CONSTRAINT ck_cashflow_kind CHECK (item_kind IN ('SCHEDULED','PURCHASE_PLAN')),
    ADD CONSTRAINT ck_cashflow_card_direction CHECK (user_card_id IS NULL OR direction = 'EXPENSE'),
    ADD CONSTRAINT ck_cashflow_plan CHECK (
        (payment_plan IS NULL AND installment_months IS NULL)
        OR (user_card_id IS NOT NULL AND payment_plan IS NOT NULL AND installment_months IS NOT NULL
            AND ((payment_plan = 'LUMP_SUM' AND installment_months = 1)
                OR (payment_plan = 'INSTALLMENT' AND installment_months >= 2)))),
    ADD CONSTRAINT ck_cashflow_purchase CHECK (
        (item_kind = 'SCHEDULED' AND purchase_state IS NULL)
        OR (item_kind = 'PURCHASE_PLAN' AND direction = 'EXPENSE' AND user_card_id IS NOT NULL
            AND amount IS NOT NULL AND payment_plan IS NOT NULL
            AND purchase_state IS NOT NULL AND purchase_state IN ('ACTIVE','FULFILLED','CANCELED')));

-- Add the self-reference only after its referenced UNIQUE index exists.
ALTER TABLE cashflow_item
    ADD CONSTRAINT fk_cashflow_previous_owner FOREIGN KEY (previous_cashflow_item_id, user_id)
    REFERENCES cashflow_item (cashflow_item_id, user_id);

CREATE TABLE cashflow_occurrence (
    cashflow_occurrence_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '예정 발생 건 번호; 실제 출금 기록 아님',
    cashflow_item_id BIGINT NOT NULL COMMENT '반복 규칙 또는 일회성 항목',
    user_id BIGINT NOT NULL COMMENT '규칙과 동일 사용자',
    occurrence_date DATE NOT NULL COMMENT '한국 기준 원래 발생일; 이번 건 날짜를 바꿔도 식별용으로 보존',
    scheduled_date DATE NOT NULL COMMENT '한국 기준 이번 건에 적용할 예정일',
    title VARCHAR(150) NOT NULL COMMENT '이번 건 이름',
    direction VARCHAR(16) NOT NULL COMMENT 'INCOME / EXPENSE',
    amount BIGINT NULL COMMENT '이번 건 예정 원화 금액; NULL이면 금액 입력 안내 후 예보 계산 제외',
    linked_account_id BIGINT NULL COMMENT '이번 건 계좌 직접 입출금 계좌; 같은 사용자 소유',
    user_card_id BIGINT NULL COMMENT '이번 건 예정 결제 카드; 같은 사용자 소유',
    category_code VARCHAR(64) NULL COMMENT '이번 건 업종 코드',
    payment_plan VARCHAR(16) NULL COMMENT 'LUMP_SUM / INSTALLMENT; 비카드 항목 NULL',
    installment_months SMALLINT NULL COMMENT '일시불 1, 할부 총 개월 수',
    status VARCHAR(24) NOT NULL COMMENT 'CONFIRMED 예정 / EXCLUDED 이번 건 제외; 기존 상태는 변환 없이 보존',
    is_overridden BOOLEAN NOT NULL DEFAULT FALSE COMMENT '이번 건만 수정했는지 여부; 이후 일괄 변경 시 판정 근거',
    memo VARCHAR(500) NULL COMMENT '이번 건 메모; 민감정보 입력 주의',
    created_at TIMESTAMP(6) NOT NULL COMMENT 'UTC 생성 시각',
    updated_at TIMESTAMP(6) NOT NULL COMMENT 'UTC 수정 시각',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'JPA 낙관적 락 버전',
    PRIMARY KEY (cashflow_occurrence_id),
    UNIQUE KEY uk_occurrence_slot (cashflow_item_id, occurrence_date),
    UNIQUE KEY uk_occurrence_owner (cashflow_occurrence_id, user_id),
    KEY ix_occurrence_owner_date (user_id, scheduled_date),
    KEY ix_occurrence_rule_owner (cashflow_item_id, user_id),
    KEY ix_occurrence_account_owner (linked_account_id, user_id),
    KEY ix_occurrence_card_owner (user_card_id, user_id),
    CONSTRAINT fk_occurrence_rule_owner FOREIGN KEY (cashflow_item_id, user_id) REFERENCES cashflow_item (cashflow_item_id, user_id),
    CONSTRAINT fk_occurrence_account_owner FOREIGN KEY (linked_account_id, user_id) REFERENCES linked_account (linked_account_id, user_id),
    CONSTRAINT fk_occurrence_card_owner FOREIGN KEY (user_card_id, user_id) REFERENCES user_card (user_card_id, user_id),
    CONSTRAINT ck_occurrence_direction CHECK (direction IN ('INCOME','EXPENSE')),
    CONSTRAINT ck_occurrence_amount CHECK (amount IS NULL OR amount > 0),
    CONSTRAINT ck_occurrence_card_direction CHECK (user_card_id IS NULL OR direction = 'EXPENSE'),
    CONSTRAINT ck_occurrence_plan CHECK (
        (payment_plan IS NULL AND installment_months IS NULL)
        OR (user_card_id IS NOT NULL AND payment_plan IS NOT NULL AND installment_months IS NOT NULL
            AND ((payment_plan = 'LUMP_SUM' AND installment_months = 1)
                OR (payment_plan = 'INSTALLMENT' AND installment_months >= 2)))),
    CONSTRAINT ck_occurrence_override CHECK (is_overridden IN (0,1)),
    CONSTRAINT ck_occurrence_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='회차별 예정 수입·지출';

-- Preserve exactly the first stored occurrence; do not invent future dates or amounts.
INSERT INTO cashflow_occurrence (cashflow_item_id, user_id, occurrence_date, scheduled_date,
    title, direction, amount, linked_account_id, user_card_id, category_code, payment_plan,
    installment_months, status, memo, created_at, updated_at, version)
SELECT cashflow_item_id, user_id, scheduled_date, scheduled_date, title, direction, amount,
    linked_account_id, user_card_id, category_code, payment_plan, installment_months,
    status, memo, created_at, updated_at, version
FROM cashflow_item;

CREATE TABLE cashflow_payment_match (
    cashflow_payment_match_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '예정 발생 건과 실제 결제 연결 번호',
    user_id BIGINT NOT NULL COMMENT '예정 발생 건·결제와 동일 사용자',
    cashflow_occurrence_id BIGINT NOT NULL COMMENT '사용자가 동일 지출로 확인한 예정 발생 건',
    payment_transaction_id BIGINT NOT NULL COMMENT '실제 YAMU 결제; 가상 구매 계획으로 생성하지 않음',
    matched_at TIMESTAMP(6) NOT NULL COMMENT 'UTC 사용자 연결 확인 시각',
    created_at TIMESTAMP(6) NOT NULL COMMENT 'UTC 생성 시각',
    PRIMARY KEY (cashflow_payment_match_id),
    UNIQUE KEY uk_match_occurrence (cashflow_occurrence_id),
    UNIQUE KEY uk_match_payment (payment_transaction_id),
    KEY ix_match_occurrence_owner (cashflow_occurrence_id, user_id),
    KEY ix_match_payment_owner (payment_transaction_id, user_id),
    CONSTRAINT fk_match_occurrence_owner FOREIGN KEY (cashflow_occurrence_id, user_id) REFERENCES cashflow_occurrence (cashflow_occurrence_id, user_id),
    CONSTRAINT fk_match_payment_owner FOREIGN KEY (payment_transaction_id, user_id) REFERENCES payment_transaction (payment_transaction_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='사용자 확인 예정 지출·실제 결제 연결';
