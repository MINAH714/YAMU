-- Approved target paths, application relationships and recommendation snapshots.
CREATE TABLE purchase_item (
 purchase_item_code VARCHAR(64) NOT NULL PRIMARY KEY,
 display_name VARCHAR(150) NOT NULL,
 is_active BOOLEAN NOT NULL DEFAULT TRUE,
 CONSTRAINT ck_purchase_item_code CHECK (REGEXP_LIKE(purchase_item_code, '^[A-Z0-9_]+$', 'c')),
 CONSTRAINT ck_purchase_item_active CHECK (is_active IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

ALTER TABLE payment_transaction
 ADD COLUMN purchase_item_code VARCHAR(64) NULL,
 ADD COLUMN recommendation_result_json JSON NULL,
 ADD CONSTRAINT fk_payment_purchase_item FOREIGN KEY (purchase_item_code) REFERENCES purchase_item(purchase_item_code) ON DELETE RESTRICT;

ALTER TABLE benefit_target
 ADD UNIQUE KEY uk_target_benefit_pair (benefit_target_id, card_benefit_id),
 DROP CHECK ck_target_shape,
 ADD CONSTRAINT ck_target_shape CHECK (
  (target_type='BRAND' AND merchant_id IS NOT NULL AND target_value IS NULL) OR
  (target_type='ALL' AND merchant_id IS NULL AND target_value IS NULL) OR
  (target_type IN ('CATEGORY','CONDITION','PURCHASE_ITEM') AND merchant_id IS NULL AND target_value IS NOT NULL));

CREATE TABLE benefit_target_group (
 benefit_target_group_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 card_benefit_id BIGINT NOT NULL,
 group_code VARCHAR(64) NOT NULL,
 is_excluded BOOLEAN NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_target_group_code (card_benefit_id, group_code),
 UNIQUE KEY uk_target_group_benefit (benefit_target_group_id, card_benefit_id),
 CONSTRAINT fk_target_group_benefit FOREIGN KEY (card_benefit_id) REFERENCES card_benefit(card_benefit_id) ON DELETE RESTRICT,
 CONSTRAINT ck_target_group_excluded CHECK (is_excluded IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE benefit_target_group_member (
 benefit_target_group_id BIGINT NOT NULL,
 benefit_target_id BIGINT NOT NULL,
 card_benefit_id BIGINT NOT NULL,
 PRIMARY KEY (benefit_target_group_id, benefit_target_id),
 CONSTRAINT fk_target_member_group FOREIGN KEY (benefit_target_group_id,card_benefit_id)
  REFERENCES benefit_target_group(benefit_target_group_id,card_benefit_id) ON DELETE RESTRICT,
 CONSTRAINT fk_target_member_target FOREIGN KEY (benefit_target_id,card_benefit_id)
  REFERENCES benefit_target(benefit_target_id,card_benefit_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Use stable semantic keys, never auto-increment IDs, for generated logical codes.
INSERT INTO benefit_target_group(card_benefit_id,group_code,is_excluded,created_at)
SELECT t.card_benefit_id, CONCAT('LEGACY_',t.is_excluded,'_',LEFT(SHA2(CONCAT(t.target_type,':',
 COALESCE(m.brand_code,t.target_value,'*')),256),40)), t.is_excluded,t.created_at
FROM benefit_target t LEFT JOIN merchant m ON m.merchant_id=t.merchant_id
WHERE t.target_type <> 'CONDITION';

-- Preserve both include/exclude paths before consolidating equal target rows.
INSERT INTO benefit_target_group_member(benefit_target_group_id,benefit_target_id,card_benefit_id)
SELECT g.benefit_target_group_id,canonical.benefit_target_id,t.card_benefit_id
FROM benefit_target t
LEFT JOIN merchant m ON m.merchant_id=t.merchant_id
JOIN benefit_target canonical ON canonical.card_benefit_id=t.card_benefit_id
 AND canonical.target_type=t.target_type AND canonical.target_key=t.target_key
 AND canonical.is_excluded=(SELECT MIN(t2.is_excluded) FROM benefit_target t2
   WHERE t2.card_benefit_id=t.card_benefit_id AND t2.target_type=t.target_type AND t2.target_key=t.target_key)
JOIN benefit_target_group g ON g.card_benefit_id=t.card_benefit_id
 AND g.group_code=CONCAT('LEGACY_',t.is_excluded,'_',LEFT(SHA2(CONCAT(t.target_type,':',COALESCE(m.brand_code,t.target_value,'*')),256),40))
WHERE t.target_type <> 'CONDITION';

DELETE duplicate FROM benefit_target duplicate JOIN benefit_target canonical
 ON canonical.card_benefit_id=duplicate.card_benefit_id AND canonical.target_type=duplicate.target_type
 AND canonical.target_key=duplicate.target_key AND canonical.is_excluded=0
WHERE duplicate.target_type <> 'CONDITION' AND duplicate.is_excluded=1;
UPDATE benefit_target SET is_excluded=0 WHERE target_type <> 'CONDITION';
ALTER TABLE benefit_target ADD CONSTRAINT ck_target_exclusion_scope CHECK (target_type='CONDITION' OR is_excluded=0);

CREATE TABLE benefit_application_group (
 benefit_application_group_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 card_product_id BIGINT NOT NULL,
 group_code VARCHAR(64) NOT NULL,
 policy_version SMALLINT NOT NULL,
 application_mode VARCHAR(16) NOT NULL,
 UNIQUE KEY uk_application_group_version(card_product_id,group_code,policy_version),
 UNIQUE KEY uk_application_group_product(benefit_application_group_id,card_product_id),
 CONSTRAINT fk_application_product FOREIGN KEY(card_product_id) REFERENCES card_product(card_product_id) ON DELETE RESTRICT,
 CONSTRAINT ck_application_version CHECK(policy_version>0),
 CONSTRAINT ck_application_mode CHECK(application_mode IN ('STACK','MAX_ONE','PRIORITY_ONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE benefit_application_member (
 benefit_application_group_id BIGINT NOT NULL,
 card_benefit_id BIGINT NOT NULL,
 card_product_id BIGINT NOT NULL,
 priority_order SMALLINT NULL,
 PRIMARY KEY(benefit_application_group_id,card_benefit_id),
 UNIQUE KEY uk_application_benefit(card_benefit_id),
 CONSTRAINT fk_application_member_group FOREIGN KEY(benefit_application_group_id,card_product_id)
  REFERENCES benefit_application_group(benefit_application_group_id,card_product_id) ON DELETE RESTRICT,
 CONSTRAINT fk_application_member_benefit FOREIGN KEY(card_benefit_id,card_product_id)
  REFERENCES card_benefit(card_benefit_id,card_product_id) ON DELETE RESTRICT,
 CONSTRAINT ck_application_priority CHECK(priority_order IS NULL OR priority_order>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

ALTER TABLE card_benefit
 ADD COLUMN fallback_benefit_id BIGINT NULL,
 ADD COLUMN fallback_mode VARCHAR(32) NULL,
 ADD CONSTRAINT ck_benefit_fallback CHECK (
  (fallback_benefit_id IS NULL AND fallback_mode IS NULL) OR
  (fallback_benefit_id IS NOT NULL AND fallback_mode IS NOT NULL AND fallback_mode='EXCESS_PAYMENT_AMOUNT')),
 ADD CONSTRAINT fk_fallback_product FOREIGN KEY(fallback_benefit_id,card_product_id)
  REFERENCES card_benefit(card_benefit_id,card_product_id);

-- 자기 참조는 RecommendationPolicyLoader에서 카드별로 검증한다.
