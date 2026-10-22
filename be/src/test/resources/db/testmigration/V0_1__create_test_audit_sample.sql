-- 테스트 전용 테이블. BaseTimeEntity(created_at·updated_at 자동 기록)와 @Version 동작을 확인한다.
-- 실제 서버 마이그레이션(src/main/resources/db/migration)에는 들어가지 않는다.
CREATE TABLE test_audit_sample (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    name       VARCHAR(50) NOT NULL,
    version    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);
