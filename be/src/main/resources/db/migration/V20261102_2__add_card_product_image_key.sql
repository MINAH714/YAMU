-- 상품 단위의 이미지 객체 키만 저장한다. 기존 상품의 이미지는 별도 매핑 전까지 NULL이다.
ALTER TABLE card_product
    ADD COLUMN image_key VARCHAR(1024) NULL COMMENT '카드 앞면 이미지 S3 객체 키; 미수집은 NULL';
