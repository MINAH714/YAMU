-- S15P31E103-405: 신용카드 마감 전 사용 추정 이력. 잔고예보가 카드마다 낸 "내일부터 이번 이용기간 마감일까지 R일" 신용 생활 소비 예측을
-- 카드·한국 날짜·R마다 그날 첫 값 한 행만 남긴다. 나중에 같은 기간의 실제 신용 생활 소비와 비교해 실사용 편향을 보는 재료다
-- (-404 Codex 검토). 체크 생활비 이력(living_cost_prediction, -309)과 같은 규칙이다. 가상 구매 결과는 저장하지 않는다.
CREATE TABLE credit_spending_prediction (
    credit_spending_prediction_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '신용 사용 추정 이력 번호',
    user_id BIGINT NOT NULL COMMENT '카드 보유 사용자',
    user_card_id BIGINT NOT NULL COMMENT '예측한 신용카드; 같은 사용자 소유',
    predicted_on DATE NOT NULL COMMENT '예측한 한국 날짜; 예측 기간은 다음 날부터 horizon_days일(이번 이용기간 마감일까지)',
    horizon_days SMALLINT NOT NULL COMMENT '예측 기간 일수 R(1 이상)',
    method VARCHAR(16) NOT NULL COMMENT 'ML_V1 AI / TSB',
    model_version VARCHAR(800) NULL COMMENT 'AI 서버가 답한 모델 버전; TSB면 NULL. 기간(7·14·30일)마다 다르면 7:버전,14:버전처럼 기간과 함께',
    projected_amount BIGINT NOT NULL COMMENT '예상 신용 생활 소비(원) = daily_amount × horizon_days; 보수적 선에서 출금일에 뺀 값',
    daily_amount BIGINT NOT NULL COMMENT '하루 예상 신용 생활 소비(원)',
    confidence VARCHAR(16) NOT NULL COMMENT 'HIGH / MEDIUM / LOW; 기록 길이 기준',
    data_origin VARCHAR(16) NOT NULL COMMENT 'REAL / SEED; 입력 90일에 모의 소비가 있으면 SEED',
    created_at TIMESTAMP(6) NOT NULL COMMENT 'UTC 저장 시각',
    PRIMARY KEY (credit_spending_prediction_id),
    UNIQUE KEY uk_credit_prediction_card_day (user_card_id, predicted_on, horizon_days),
    KEY ix_credit_prediction_card_owner (user_card_id, user_id),
    KEY ix_credit_prediction_owner_day (user_id, predicted_on),
    CONSTRAINT fk_credit_prediction_card_owner FOREIGN KEY (user_card_id, user_id) REFERENCES user_card (user_card_id, user_id),
    CONSTRAINT ck_credit_prediction_horizon CHECK (horizon_days >= 1),
    CONSTRAINT ck_credit_prediction_method CHECK (method IN ('ML_V1','TSB')),
    CONSTRAINT ck_credit_prediction_model_version CHECK ((method = 'ML_V1') = (model_version IS NOT NULL)),
    CONSTRAINT ck_credit_prediction_amount CHECK (projected_amount >= 0 AND daily_amount >= 0),
    CONSTRAINT ck_credit_prediction_confidence CHECK (confidence IN ('HIGH','MEDIUM','LOW')),
    CONSTRAINT ck_credit_prediction_origin CHECK (data_origin IN ('REAL','SEED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='신용카드별 마감 전 사용 추정 이력(그날 첫 값)';
