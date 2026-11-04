-- Approved composition overlay. Apply after 01/02; repeatable, no historical payment rewrite.
-- NORI2 App Store + YouTube Premium: user-reported issuer telephone consultation.
-- HYUNDAI M excess principal split: approved design interpretation of published fallback wording.
SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO purchase_item(purchase_item_code,display_name,is_active)
SELECT 'YOUTUBE_PREMIUM_SUBSCRIPTION','유튜브 프리미엄 구독',TRUE
WHERE NOT EXISTS(SELECT 1 FROM purchase_item WHERE purchase_item_code='YOUTUBE_PREMIUM_SUBSCRIPTION');

INSERT INTO benefit_target(card_benefit_id,target_type,merchant_id,target_value,is_excluded,created_at)
SELECT b.card_benefit_id,'PURCHASE_ITEM',NULL,'YOUTUBE_PREMIUM_SUBSCRIPTION',0,UTC_TIMESTAMP(6)
FROM card_benefit b JOIN card_product p USING(card_product_id)
WHERE p.issuer_code='KB' AND p.product_name='KB국민 노리2 체크카드(KB Pay)' AND p.card_type='CHECK'
 AND b.benefit_code='SUBSCRIPTION_1000' AND b.policy_version=1
 AND NOT EXISTS(SELECT 1 FROM benefit_target t WHERE t.card_benefit_id=b.card_benefit_id AND t.target_type='PURCHASE_ITEM' AND t.target_value='YOUTUBE_PREMIUM_SUBSCRIPTION');
INSERT INTO benefit_target(card_benefit_id,target_type,merchant_id,target_value,is_excluded,created_at)
SELECT b.card_benefit_id,'BRAND',m.merchant_id,NULL,0,UTC_TIMESTAMP(6)
FROM card_benefit b JOIN card_product p USING(card_product_id) JOIN merchant m ON m.brand_code='APP_STORE'
WHERE p.issuer_code='KB' AND p.product_name='KB국민 노리2 체크카드(KB Pay)' AND p.card_type='CHECK'
 AND b.benefit_code='SUBSCRIPTION_1000' AND b.policy_version=1
 AND NOT EXISTS(SELECT 1 FROM benefit_target t WHERE t.card_benefit_id=b.card_benefit_id AND t.target_type='BRAND' AND t.merchant_id=m.merchant_id);
INSERT INTO benefit_target_group(card_benefit_id,group_code,is_excluded,created_at)
SELECT b.card_benefit_id,'APP_STORE_YOUTUBE_PREMIUM',0,UTC_TIMESTAMP(6)
FROM card_benefit b JOIN card_product p USING(card_product_id)
WHERE p.issuer_code='KB' AND p.product_name='KB국민 노리2 체크카드(KB Pay)' AND p.card_type='CHECK'
 AND b.benefit_code='SUBSCRIPTION_1000' AND b.policy_version=1
 AND NOT EXISTS(SELECT 1 FROM benefit_target_group g WHERE g.card_benefit_id=b.card_benefit_id AND g.group_code='APP_STORE_YOUTUBE_PREMIUM');
INSERT INTO benefit_target_group_member(benefit_target_group_id,benefit_target_id,card_benefit_id)
SELECT g.benefit_target_group_id,t.benefit_target_id,g.card_benefit_id
FROM benefit_target_group g JOIN benefit_target t ON t.card_benefit_id=g.card_benefit_id
LEFT JOIN merchant m ON m.merchant_id=t.merchant_id
WHERE g.group_code='APP_STORE_YOUTUBE_PREMIUM' AND ((t.target_type='BRAND' AND m.brand_code='APP_STORE')
 OR (t.target_type='PURCHASE_ITEM' AND t.target_value='YOUTUBE_PREMIUM_SUBSCRIPTION'))
 AND NOT EXISTS(SELECT 1 FROM benefit_target_group_member x WHERE x.benefit_target_group_id=g.benefit_target_group_id AND x.benefit_target_id=t.benefit_target_id);

CREATE TEMPORARY TABLE composition_seed(
 issuer_code VARCHAR(32), product_name VARCHAR(150), card_type VARCHAR(16), group_code VARCHAR(64), application_mode VARCHAR(16),
 benefit_code VARCHAR(64), priority_order SMALLINT NULL);
INSERT INTO composition_seed VALUES
('KB','KB국민 노리2 체크카드(KB Pay)','CHECK','MOBILE_SUBSCRIPTION','STACK','MOBILE_10',NULL),
('KB','KB국민 노리2 체크카드(KB Pay)','CHECK','MOBILE_SUBSCRIPTION','STACK','SUBSCRIPTION_1000',NULL),
('HYUNDAI','현대카드 M ME4','CREDIT','PREFERRED_BASE','PRIORITY_ONE','FOOD_5',1),
('HYUNDAI','현대카드 M ME4','CREDIT','PREFERRED_BASE','PRIORITY_ONE','OVERSEAS_5',1),
('HYUNDAI','현대카드 M ME4','CREDIT','PREFERRED_BASE','PRIORITY_ONE','BASE_15',2);
INSERT INTO composition_seed
SELECT 'KAKAOBANK','카카오뱅크 프렌즈 체크','CHECK','BASE_PROMO','STACK',b.benefit_code,NULL
FROM card_benefit b JOIN card_product p USING(card_product_id)
WHERE p.issuer_code='KAKAOBANK' AND p.product_name='카카오뱅크 프렌즈 체크' AND p.card_type='CHECK' AND b.policy_version=1
 AND (b.benefit_code='BASE_WEEKDAY' OR b.benefit_code IN ('PROMO_OIL','PROMO_TELECOM','PROMO_ACADEMY','PROMO_TAXI'));
CREATE TEMPORARY TABLE composition_guard(valid BOOLEAN NOT NULL CHECK(valid=1));
-- Existing version conflicts are errors, never silently rewritten or attached to another group.
INSERT INTO composition_guard
SELECT 0 FROM composition_seed s JOIN card_product p
 ON p.issuer_code=s.issuer_code AND p.product_name=s.product_name AND p.card_type=s.card_type
JOIN benefit_application_group g ON g.card_product_id=p.card_product_id AND g.group_code=s.group_code AND g.policy_version=1
WHERE g.application_mode<>s.application_mode LIMIT 1;
INSERT INTO composition_guard
SELECT 0 FROM composition_seed s JOIN card_product p
 ON p.issuer_code=s.issuer_code AND p.product_name=s.product_name AND p.card_type=s.card_type
JOIN card_benefit b ON b.card_product_id=p.card_product_id AND b.benefit_code=s.benefit_code AND b.policy_version=1
JOIN benefit_application_member m ON m.card_benefit_id=b.card_benefit_id
JOIN benefit_application_group g ON g.benefit_application_group_id=m.benefit_application_group_id
WHERE g.group_code<>s.group_code OR g.policy_version<>1 OR NOT(m.priority_order <=> s.priority_order) LIMIT 1;
INSERT INTO benefit_application_group(card_product_id,group_code,policy_version,application_mode)
SELECT DISTINCT p.card_product_id,s.group_code,1,s.application_mode FROM composition_seed s JOIN card_product p
 ON p.issuer_code=s.issuer_code AND p.product_name=s.product_name AND p.card_type=s.card_type
WHERE NOT EXISTS(SELECT 1 FROM benefit_application_group g WHERE g.card_product_id=p.card_product_id AND g.group_code=s.group_code AND g.policy_version=1);
INSERT INTO benefit_application_member(benefit_application_group_id,card_benefit_id,card_product_id,priority_order)
SELECT g.benefit_application_group_id,b.card_benefit_id,p.card_product_id,s.priority_order
FROM composition_seed s JOIN card_product p ON p.issuer_code=s.issuer_code AND p.product_name=s.product_name AND p.card_type=s.card_type
JOIN card_benefit b ON b.card_product_id=p.card_product_id AND b.benefit_code=s.benefit_code AND b.policy_version=1
JOIN benefit_application_group g ON g.card_product_id=p.card_product_id AND g.group_code=s.group_code AND g.policy_version=1
WHERE NOT EXISTS(SELECT 1 FROM benefit_application_member x WHERE x.card_benefit_id=b.card_benefit_id);
UPDATE card_benefit preferred JOIN card_product p ON p.card_product_id=preferred.card_product_id
JOIN card_benefit base ON base.card_product_id=preferred.card_product_id AND base.benefit_code='BASE_15' AND base.policy_version=preferred.policy_version
SET preferred.fallback_benefit_id=base.card_benefit_id,preferred.fallback_mode='EXCESS_PAYMENT_AMOUNT'
WHERE p.issuer_code='HYUNDAI' AND p.product_name='현대카드 M ME4' AND p.card_type='CREDIT'
 AND preferred.benefit_code IN ('FOOD_5','OVERSEAS_5') AND preferred.policy_version=1 AND preferred.fallback_benefit_id IS NULL;
DROP TEMPORARY TABLE composition_seed,composition_guard;
COMMIT;
