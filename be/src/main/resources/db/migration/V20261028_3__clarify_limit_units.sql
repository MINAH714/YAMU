-- S15P31E103-151: distinguish won, points and counts without changing limit values.
-- Keep a bounded transition constraint while converting existing AMOUNT rows.
ALTER TABLE limit_group
    DROP CHECK ck_limit_unit,
    ADD CONSTRAINT ck_limit_unit_transition CHECK (limit_unit IN ('AMOUNT','KRW','POINT','COUNT'));

UPDATE limit_group SET limit_unit = 'KRW' WHERE limit_unit = 'AMOUNT';

ALTER TABLE limit_group
    DROP CHECK ck_limit_unit_transition,
    MODIFY COLUMN limit_unit VARCHAR(16) NOT NULL COMMENT 'KRW: 원 / POINT: 포인트 / COUNT: 횟수; 한도값·사용량의 단위',
    ADD CONSTRAINT ck_limit_unit CHECK (limit_unit IN ('KRW','POINT','COUNT'));
