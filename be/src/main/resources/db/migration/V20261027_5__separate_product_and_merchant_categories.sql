-- S15P31E103-145: explicit merchant industry names and purchase product fields.
-- RENAME preserves existing industry codes and indexes. Do not reinterpret them.
ALTER TABLE merchant
    RENAME COLUMN category_code TO merchant_category_code;

ALTER TABLE cashflow_item
    RENAME COLUMN category_code TO merchant_category_code,
    ADD COLUMN product_category_code VARCHAR(64) NULL COMMENT '구매할 물품 카테고리 코드; API productCategoryCode, 미지정 NULL',
    ADD COLUMN product_name VARCHAR(150) NULL COMMENT '구매할 상품명; API productName, 일정 표시 이름 title과 별도';

ALTER TABLE cashflow_occurrence
    RENAME COLUMN category_code TO merchant_category_code,
    ADD COLUMN product_category_code VARCHAR(64) NULL COMMENT '이번 건 물품 카테고리 코드; API productCategoryCode, 미지정 NULL',
    ADD COLUMN product_name VARCHAR(150) NULL COMMENT '이번 건 구매할 상품명; API productName, 일정 표시 이름 title과 별도';

-- Product data for old rows is unknown. Do not infer it from title or industry.
