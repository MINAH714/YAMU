-- S15P31E103-309: 생활비 예측 이력. 잔고예보·생활비 상세가 계좌마다 낸 예상 생활비를
-- 계좌·한국 날짜·기간마다 그날 첫 값 한 행만 남긴다. 나중에 같은 기간의 실제 생활 소비와 비교하는 재료다.
-- 예보 날짜별 잔액 선과 가상 구매 결과는 여전히 저장하지 않는다.
CREATE TABLE living_cost_prediction (
    living_cost_prediction_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '생활비 예측 이력 번호',
    user_id BIGINT NOT NULL COMMENT '계좌 소유 사용자',
    linked_account_id BIGINT NOT NULL COMMENT '예측한 연결 계좌; 같은 사용자 소유',
    predicted_on DATE NOT NULL COMMENT '예측한 한국 날짜; 예측 기간은 다음 날부터 horizon_days일',
    horizon_days SMALLINT NOT NULL COMMENT '예측 기간 일수 N(1 이상)',
    method VARCHAR(16) NOT NULL COMMENT 'ML_V1 AI / TSB',
    projected_amount BIGINT NOT NULL COMMENT '예상 생활비(원) = daily_amount × horizon_days',
    daily_amount BIGINT NOT NULL COMMENT '하루 예상 생활비(원); 잔고예보 보수적 선에서 날마다 뺀 값',
    confidence VARCHAR(16) NOT NULL COMMENT 'HIGH / MEDIUM / LOW; 기록 길이 기준',
    data_origin VARCHAR(16) NOT NULL COMMENT 'REAL / SEED; 입력 90일에 모의 소비가 있으면 SEED',
    created_at TIMESTAMP(6) NOT NULL COMMENT 'UTC 저장 시각',
    PRIMARY KEY (living_cost_prediction_id),
    UNIQUE KEY uk_prediction_account_day (linked_account_id, predicted_on, horizon_days),
    KEY ix_prediction_account_owner (linked_account_id, user_id),
    KEY ix_prediction_owner_day (user_id, predicted_on),
    CONSTRAINT fk_prediction_account_owner FOREIGN KEY (linked_account_id, user_id) REFERENCES linked_account (linked_account_id, user_id),
    CONSTRAINT ck_prediction_horizon CHECK (horizon_days >= 1),
    CONSTRAINT ck_prediction_method CHECK (method IN ('ML_V1','TSB')),
    CONSTRAINT ck_prediction_amount CHECK (projected_amount >= 0 AND daily_amount >= 0),
    CONSTRAINT ck_prediction_confidence CHECK (confidence IN ('HIGH','MEDIUM','LOW')),
    CONSTRAINT ck_prediction_origin CHECK (data_origin IN ('REAL','SEED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='계좌별 생활비 예측 이력(그날 첫 값)';
