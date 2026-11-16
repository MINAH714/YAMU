-- EXP-005 RESEARCH ONLY. Explicit isolated-test loading; not registered with seedLocal.
-- Incomplete policies MUST NOT be treated as service-ready. Missing validity/rounding stage stay NULL.
-- Evidence: official Deep Dream credit/check product pages, checked 2026-11-13.
-- No personal transactions, merchant IDs or user-card numbers are stored here.
SET NAMES utf8mb4;
START TRANSACTION;
CREATE TEMPORARY TABLE exp005_products(kind VARCHAR(16), name VARCHAR(150), url VARCHAR(1000), base_rate DECIMAL(9,6), extra_rate DECIMAL(9,6), minimum BIGINT);
INSERT INTO exp005_products VALUES
('CREDIT','신한카드 Deep Dream','https://www.shinhancard.com/pconts/html/card/apply/credit/1188220_2207.html',0.007,0.014,300000),
('CHECK','신한카드 Deep Dream 체크','https://www.shinhancard.com/pconts/html/card/apply/check/1188313_2206.html',0.002,0.004,200000);
INSERT INTO card_product(issuer_code,product_name,card_type,terms_url,created_at,updated_at)
SELECT 'SHINHAN',s.name,s.kind,s.url,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM exp005_products s
WHERE NOT EXISTS(SELECT 1 FROM card_product p WHERE p.issuer_code='SHINHAN' AND p.product_name=s.name AND p.card_type=s.kind);
CREATE TEMPORARY TABLE exp005_benefits(product BIGINT, code VARCHAR(64), name VARCHAR(150), rate DECIMAL(9,6), minimum BIGINT, url VARCHAR(1000));
INSERT INTO exp005_benefits
SELECT p.card_product_id,'DD_BASE','모두드림 기본 적립',s.base_rate,0,s.url FROM exp005_products s JOIN card_product p ON p.issuer_code='SHINHAN' AND p.product_name=s.name AND p.card_type=s.kind;
INSERT INTO exp005_benefits
SELECT p.card_product_id,'DD_DREAM_EXTRA','더해드림 추가분',s.extra_rate,s.minimum,s.url FROM exp005_products s JOIN card_product p ON p.issuer_code='SHINHAN' AND p.product_name=s.name AND p.card_type=s.kind;
INSERT INTO exp005_benefits
SELECT p.card_product_id,'DD_DEFERRED_EXTRA','챙겨드림 사후 추가분 - 거래 점수 사용 금지',s.extra_rate,s.minimum,s.url FROM exp005_products s JOIN card_product p ON p.issuer_code='SHINHAN' AND p.product_name=s.name AND p.card_type=s.kind;
INSERT INTO exp005_benefits
SELECT p.card_product_id,'DD_BASE_BONUS','모두드림 조건부 추가 0.1%',0.001,0,s.url FROM exp005_products s JOIN card_product p ON p.issuer_code='SHINHAN' AND p.product_name=s.name AND p.card_type=s.kind WHERE s.kind='CREDIT';
CREATE TEMPORARY TABLE exp005_guard(ok BOOLEAN NOT NULL CHECK(ok=1));
-- Reruns never overwrite divergent policy rows. Abort instead.
INSERT INTO exp005_guard
SELECT 0 FROM exp005_benefits s JOIN card_benefit b ON b.card_product_id=s.product AND b.benefit_code=s.code AND b.policy_version=1
WHERE NOT(b.rate <=> s.rate) OR b.min_performance_amount<>s.minimum OR b.value_unit<>'POINT' OR NOT(b.point_krw_rate <=> 1)
 OR b.benefit_type<>'POINT' OR b.calculation_type<>'RATE' OR NOT(b.rounding_mode <=> 'DOWN') OR NOT(b.rounding_unit <=> 1)
 OR b.min_payment_amount<>0 OR b.rounding_stage IS NOT NULL OR b.valid_from IS NOT NULL OR b.valid_until IS NOT NULL LIMIT 1;
INSERT INTO card_benefit(card_product_id,benefit_code,policy_version,benefit_name,benefit_type,calculation_type,rate,value_unit,point_krw_rate,
 application_timing,rounding_mode,rounding_unit,rounding_stage,min_payment_amount,min_performance_amount,terms_url,created_at)
SELECT s.product,s.code,1,s.name,'POINT','RATE',s.rate,'POINT',1,'POINT_ACCRUAL','DOWN',1,NULL,0,s.minimum,s.url,UTC_TIMESTAMP(6)
FROM exp005_benefits s WHERE NOT EXISTS(SELECT 1 FROM card_benefit b WHERE b.card_product_id=s.product AND b.benefit_code=s.code AND b.policy_version=1);
INSERT INTO limit_group(card_product_id,group_code,policy_version,group_name,period_type,limit_unit,base_limit_value,created_at)
SELECT p.card_product_id,'DD_EXTRA_MONTHLY',1,'더해드림 및 챙겨드림 추가분 공유 한도','MONTHLY','POINT',NULL,UTC_TIMESTAMP(6)
FROM exp005_products s JOIN card_product p ON p.issuer_code='SHINHAN' AND p.product_name=s.name AND p.card_type=s.kind
WHERE NOT EXISTS(SELECT 1 FROM limit_group g WHERE g.card_product_id=p.card_product_id AND g.group_code='DD_EXTRA_MONTHLY' AND g.policy_version=1);
INSERT INTO exp005_guard
SELECT 0 FROM exp005_products s JOIN card_product p ON p.issuer_code='SHINHAN' AND p.product_name=s.name AND p.card_type=s.kind
JOIN limit_group g ON g.card_product_id=p.card_product_id AND g.group_code='DD_EXTRA_MONTHLY' AND g.policy_version=1
WHERE g.period_type<>'MONTHLY' OR g.limit_unit<>'POINT' OR g.base_limit_value IS NOT NULL OR g.valid_from IS NOT NULL OR g.valid_until IS NOT NULL LIMIT 1;
CREATE TEMPORARY TABLE exp005_tiers(kind VARCHAR(16), lower_amount BIGINT, upper_amount BIGINT NULL, maximum BIGINT);
INSERT INTO exp005_tiers VALUES ('CREDIT',0,300000,0),('CREDIT',300000,600000,10000),('CREDIT',600000,1200000,30000),('CREDIT',1200000,NULL,50000),
 ('CHECK',0,200000,0),('CHECK',200000,400000,5000),('CHECK',400000,800000,15000),('CHECK',800000,NULL,30000);
INSERT INTO exp005_guard
SELECT 0 FROM exp005_tiers t JOIN exp005_products s ON s.kind=t.kind JOIN card_product p ON p.issuer_code='SHINHAN' AND p.product_name=s.name AND p.card_type=s.kind
JOIN limit_group g ON g.card_product_id=p.card_product_id AND g.group_code='DD_EXTRA_MONTHLY' AND g.policy_version=1
JOIN tier_limit l ON l.limit_group_id=g.limit_group_id AND l.min_performance_amount=t.lower_amount
WHERE l.limit_value<>t.maximum OR NOT(l.max_performance_amount <=> t.upper_amount) LIMIT 1;
INSERT INTO tier_limit(limit_group_id,min_performance_amount,max_performance_amount,limit_value,created_at)
SELECT g.limit_group_id,t.lower_amount,t.upper_amount,t.maximum,UTC_TIMESTAMP(6) FROM exp005_tiers t
JOIN exp005_products s ON s.kind=t.kind JOIN card_product p ON p.issuer_code='SHINHAN' AND p.product_name=s.name AND p.card_type=s.kind
JOIN limit_group g ON g.card_product_id=p.card_product_id AND g.group_code='DD_EXTRA_MONTHLY' AND g.policy_version=1
WHERE NOT EXISTS(SELECT 1 FROM tier_limit l WHERE l.limit_group_id=g.limit_group_id AND l.min_performance_amount=t.lower_amount);
INSERT INTO benefit_limit_link(card_product_id,card_benefit_id,limit_group_id,created_at)
SELECT b.card_product_id,b.card_benefit_id,g.limit_group_id,UTC_TIMESTAMP(6) FROM exp005_benefits s
JOIN card_benefit b ON b.card_product_id=s.product AND b.benefit_code=s.code AND b.policy_version=1
JOIN limit_group g ON g.card_product_id=s.product AND g.group_code='DD_EXTRA_MONTHLY' AND g.policy_version=1
WHERE s.code IN ('DD_DREAM_EXTRA','DD_DEFERRED_EXTRA') AND NOT EXISTS(SELECT 1 FROM benefit_limit_link l WHERE l.card_benefit_id=b.card_benefit_id AND l.limit_group_id=g.limit_group_id);
-- Coarse evidence predicates, NOT name-based merchant classification. The adapter requires a provenance record.
INSERT INTO benefit_target(card_benefit_id,target_type,target_value,is_excluded,created_at)
SELECT b.card_benefit_id,'CONDITION',CASE s.code WHEN 'DD_BASE' THEN 'DD:POINT_ELIGIBLE' WHEN 'DD_BASE_BONUS' THEN 'DD:BONUS_ELIGIBLE'
 WHEN 'DD_DREAM_EXTRA' THEN 'DD:DREAM_ELIGIBLE' ELSE 'DD:DEFERRED_SETTLEMENT_CONFIRMED' END,0,UTC_TIMESTAMP(6)
FROM exp005_benefits s JOIN card_benefit b ON b.card_product_id=s.product AND b.benefit_code=s.code AND b.policy_version=1
WHERE NOT EXISTS(SELECT 1 FROM benefit_target t WHERE t.card_benefit_id=b.card_benefit_id);
-- Deliberately no application group: stacking/rounding settlement evidence is still incomplete.
DROP TEMPORARY TABLE exp005_guard;
DROP TEMPORARY TABLE exp005_tiers;
DROP TEMPORARY TABLE exp005_benefits;
DROP TEMPORARY TABLE exp005_products;
COMMIT;
