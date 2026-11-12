-- S15P31E103-375: 실적 유불리 분석을 보고 사용자가 고른 이번 달 판단(채우기·평소대로·안 채우기)을 카드·월당 한 줄로 남긴다.
-- 같은 카드·월에 다시 보내면 이 줄을 덮어쓰고, "다시 판단하기"(decision null)는 줄을 남긴 채 decision만 비운다(2026-11-10 결정).
-- 판단이 있으면 그달 그 카드의 실적 부족 알림을 멈춘다(notifications_muted). 알림 생성(S15P31E103-377)이 이 칸을 읽는다.
-- analysis_version은 앱이 근거로 본 분석의 asOf(UTC 시각 문자열)를 그대로 둔다. 같은 달의 옛 버전도 받는다(비교용).
-- guide는 기록할 때 서버가 다시 계산한 판정이다. 데이터가 모자라 판정할 수 없으면 NULL.
-- 카드 행이 지워지면(로컬 시드 초기화) 판단도 의미가 없어 같이 지운다(ON DELETE CASCADE).
CREATE TABLE performance_decision (
    performance_decision_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '실적 판단 기록 번호',
    user_id BIGINT NOT NULL COMMENT '판단한 사용자',
    user_card_id BIGINT NOT NULL COMMENT '판단한 카드',
    performance_month DATE NOT NULL COMMENT '판단한 실적 월(한국 기준)의 1일',
    decision VARCHAR(16) NULL COMMENT 'FILL 채우기 · KEEP 평소대로 · SKIP 안 채우기; NULL은 되돌린 상태',
    guide VARCHAR(40) NULL COMMENT '기록할 때 서버 판정(PerformanceGuide); 판정할 수 없으면 NULL',
    analysis_version VARCHAR(40) NULL COMMENT '앱이 근거로 본 분석의 asOf; 보내지 않았으면 NULL',
    notifications_muted BOOLEAN NOT NULL COMMENT '그달 이 카드 실적 부족 알림 멈춤 여부; 되돌리면 false',
    decided_at TIMESTAMP(6) NOT NULL COMMENT 'UTC 마지막으로 판단·되돌린 시각',
    created_at TIMESTAMP(6) NOT NULL COMMENT 'UTC 생성 시각',
    updated_at TIMESTAMP(6) NOT NULL COMMENT 'UTC 수정 시각',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'JPA 낙관적 락 버전',
    PRIMARY KEY (performance_decision_id),
    UNIQUE KEY uk_performance_decision_card_month (user_card_id, performance_month),
    KEY ix_performance_decision_owner (user_id, performance_month),
    CONSTRAINT fk_performance_decision_card_owner FOREIGN KEY (user_card_id, user_id)
        REFERENCES user_card (user_card_id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_performance_decision_month CHECK (DAYOFMONTH(performance_month) = 1),
    CONSTRAINT ck_performance_decision_value CHECK (decision IS NULL OR decision IN ('FILL', 'KEEP', 'SKIP')),
    CONSTRAINT ck_performance_decision_muted CHECK (decision IS NOT NULL OR notifications_muted = FALSE),
    CONSTRAINT ck_performance_decision_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='실적 유불리 판단 기록(카드·월당 한 줄)';
