-- S15P31E103-551. Explicit QA catalog; 2026-11 runtime scope is separate from official validity.
-- NULL rounding/timing/daily maximum are UNKNOWN, not zero/unlimited. No bank calls.
-- Apply after 01/02/03 in one connection; abort on error (never mysql --force).
SET NAMES utf8mb4;
START TRANSACTION;
SET @ibk551_url='https://www.ibk.co.kr/cardbiz/detailBizNew.ibk?PDCD=0001&PDGR_CD=11&PDLN_CD=12&PDTM_CD=296&pageId=CA01030000';
CREATE TEMPORARY TABLE ibk551_guard(ok BOOLEAN NOT NULL CHECK(ok=1));
CREATE TEMPORARY TABLE ibk551_merchants(code VARCHAR(64),name VARCHAR(150),category VARCHAR(64));
INSERT INTO ibk551_merchants VALUES
('EMART24','이마트24','CONVENIENCE_STORE'),
('MEGA_MGC','메가MGC커피','COFFEE_SHOP'),
('MUSINSA','무신사','ONLINE_MARKETPLACE'),
('NAVER_PLUS_STORE','네이버플러스스토어','ONLINE_MARKETPLACE'),
('GMARKET','G마켓','ONLINE_MARKETPLACE'),
('NOL','NOL(야놀자)','OTHER'),
('SOCAR','쏘카','OTHER');
INSERT INTO ibk551_guard SELECT 0 FROM ibk551_merchants s JOIN merchant m ON m.brand_code=s.code WHERE m.merchant_category_code<>s.category LIMIT 1;
INSERT INTO merchant(merchant_name,brand_code,merchant_category_code,created_at,updated_at)
SELECT s.name,s.code,s.category,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM ibk551_merchants s WHERE NOT EXISTS(SELECT 1 FROM merchant m WHERE m.brand_code=s.code);
INSERT INTO card_product(issuer_code,product_name,card_type,terms_url,created_at,updated_at)
SELECT 'IBK','IBK나라사랑카드','CHECK',@ibk551_url,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
WHERE NOT EXISTS(SELECT 1 FROM card_product WHERE issuer_code='IBK' AND product_name='IBK나라사랑카드' AND card_type='CHECK');
SET @ibk551_product=(SELECT card_product_id FROM card_product WHERE issuer_code='IBK' AND product_name='IBK나라사랑카드' AND card_type='CHECK');
CREATE TEMPORARY TABLE ibk551_benefits(code VARCHAR(64),name VARCHAR(150),kind VARCHAR(16),rate DECIMAL(9,6),timing VARCHAR(32),minimum BIGINT);
INSERT INTO ibk551_benefits VALUES
('GS25_INSTANT_10','GS25 대상 품목 10% 현장할인','DISCOUNT',0.1,'AT_APPROVAL',0),
('NARA_CONVENIENCE','나라 편의점 청구할인(계좌환급 예상)','CASHBACK',0.1,NULL,80000),
('AIO_CONVENIENCE','All in One 편의점 청구할인(계좌환급 예상)','CASHBACK',0.1,NULL,250000),
('AIO_CAFE','All in One 카페 청구할인(계좌환급 예상)','CASHBACK',0.1,NULL,250000),
('AIO_MEDICAL','All in One 병원·약국 청구할인(계좌환급 예상)','CASHBACK',0.1,NULL,250000),
('AIO_SHOPPING','All in One 쇼핑·배달 청구할인(계좌환급 예상)','CASHBACK',0.1,NULL,250000),
('AIO_LEISURE','All in One 여행·레저 청구할인(계좌환급 예상)','CASHBACK',0.1,NULL,250000),
('OUTBACK_INSTANT','아웃백 매장 20% 현장할인','DISCOUNT',0.2,'AT_APPROVAL',200000);
INSERT INTO ibk551_guard SELECT 0 FROM card_benefit b LEFT JOIN ibk551_benefits s ON b.benefit_code=s.code
WHERE b.card_product_id=@ibk551_product AND (s.code IS NULL OR b.policy_version<>1 OR b.benefit_type<>s.kind
 OR b.calculation_type<>'RATE' OR NOT(b.rate <=> s.rate) OR b.value_unit<>'KRW' OR NOT(b.application_timing <=> s.timing)
 OR b.min_payment_amount<>0 OR b.min_performance_amount<>s.minimum OR b.rounding_mode IS NOT NULL OR b.rounding_unit IS NOT NULL
 OR b.rounding_stage IS NOT NULL OR b.valid_from IS NOT NULL OR b.valid_until IS NOT NULL OR b.conditions_json IS NOT NULL) LIMIT 1;
INSERT INTO card_benefit(card_product_id,benefit_code,policy_version,benefit_name,benefit_type,calculation_type,rate,value_unit,application_timing,min_payment_amount,min_performance_amount,terms_url,created_at)
SELECT @ibk551_product,s.code,1,s.name,s.kind,'RATE',s.rate,'KRW',s.timing,0,s.minimum,@ibk551_url,UTC_TIMESTAMP(6)
FROM ibk551_benefits s WHERE NOT EXISTS(SELECT 1 FROM card_benefit b WHERE b.card_product_id=@ibk551_product AND b.benefit_code=s.code AND b.policy_version=1);
CREATE TEMPORARY TABLE ibk551_limits(code VARCHAR(64),period VARCHAR(16),unit VARCHAR(16),maximum BIGINT NULL);
INSERT INTO ibk551_limits VALUES
('GS25_INSTANT_DAILY','DAILY','COUNT',1),
('AIO_MONTHLY','MONTHLY','KRW',NULL),
('NARA_CONVENIENCE_PER_TX','PER_TRANSACTION','KRW',1000),
('NARA_CONVENIENCE_MONTH_COUNT','MONTHLY','COUNT',2),
('AIO_CONVENIENCE_PER_TX','PER_TRANSACTION','KRW',1000),
('AIO_CONVENIENCE_MONTH_COUNT','MONTHLY','COUNT',10),
('AIO_CONVENIENCE_DAY_COUNT','DAILY','COUNT',NULL),
('AIO_CAFE_PER_TX','PER_TRANSACTION','KRW',1000),
('AIO_CAFE_MONTH_COUNT','MONTHLY','COUNT',2),
('AIO_CAFE_DAY_COUNT','DAILY','COUNT',1),
('AIO_MEDICAL_PER_TX','PER_TRANSACTION','KRW',1000),
('AIO_MEDICAL_MONTH_COUNT','MONTHLY','COUNT',2),
('AIO_MEDICAL_DAY_COUNT','DAILY','COUNT',1),
('AIO_SHOPPING_PER_TX','PER_TRANSACTION','KRW',1000),
('AIO_SHOPPING_MONTH_COUNT','MONTHLY','COUNT',2),
('AIO_SHOPPING_DAY_COUNT','DAILY','COUNT',1),
('AIO_LEISURE_PER_TX','PER_TRANSACTION','KRW',1000),
('AIO_LEISURE_MONTH_COUNT','MONTHLY','COUNT',2),
('AIO_LEISURE_DAY_COUNT','DAILY','COUNT',1),
('OUTBACK_INSTANT_PER_TX','PER_TRANSACTION','KRW',40000),
('OUTBACK_INSTANT_MONTH_COUNT','MONTHLY','COUNT',1);
INSERT INTO ibk551_guard SELECT 0 FROM limit_group g LEFT JOIN ibk551_limits s ON g.group_code=s.code
WHERE g.card_product_id=@ibk551_product AND (s.code IS NULL OR g.policy_version<>1 OR g.period_type<>s.period OR g.limit_unit<>s.unit
 OR NOT(g.base_limit_value <=> s.maximum) OR g.valid_from IS NOT NULL OR g.valid_until IS NOT NULL) LIMIT 1;
INSERT INTO limit_group(card_product_id,group_code,policy_version,group_name,period_type,limit_unit,base_limit_value,created_at)
SELECT @ibk551_product,s.code,1,s.code,s.period,s.unit,s.maximum,UTC_TIMESTAMP(6) FROM ibk551_limits s
WHERE NOT EXISTS(SELECT 1 FROM limit_group g WHERE g.card_product_id=@ibk551_product AND g.group_code=s.code AND g.policy_version=1);
CREATE TEMPORARY TABLE ibk551_tiers(lo BIGINT,hi BIGINT NULL,maximum BIGINT);
INSERT INTO ibk551_tiers VALUES (0,250000,0),(250000,500000,10000),(500000,1000000,25000),(1000000,NULL,45000);
INSERT INTO ibk551_guard SELECT 0 FROM tier_limit t JOIN limit_group g USING(limit_group_id) LEFT JOIN ibk551_tiers s ON t.min_performance_amount=s.lo
WHERE g.card_product_id=@ibk551_product AND g.group_code='AIO_MONTHLY' AND (s.lo IS NULL OR NOT(t.max_performance_amount <=> s.hi) OR t.limit_value<>s.maximum) LIMIT 1;
INSERT INTO tier_limit(limit_group_id,min_performance_amount,max_performance_amount,limit_value,created_at)
SELECT g.limit_group_id,s.lo,s.hi,s.maximum,UTC_TIMESTAMP(6) FROM limit_group g CROSS JOIN ibk551_tiers s
WHERE g.card_product_id=@ibk551_product AND g.group_code='AIO_MONTHLY' AND NOT EXISTS(SELECT 1 FROM tier_limit t WHERE t.limit_group_id=g.limit_group_id AND t.min_performance_amount=s.lo);
CREATE TEMPORARY TABLE ibk551_links(benefit VARCHAR(64),lim VARCHAR(64));
INSERT INTO ibk551_links VALUES
('GS25_INSTANT_10','GS25_INSTANT_DAILY'),
('NARA_CONVENIENCE','NARA_CONVENIENCE_PER_TX'),
('NARA_CONVENIENCE','NARA_CONVENIENCE_MONTH_COUNT'),
('AIO_CONVENIENCE','AIO_CONVENIENCE_PER_TX'),
('AIO_CONVENIENCE','AIO_CONVENIENCE_MONTH_COUNT'),
('AIO_CONVENIENCE','AIO_CONVENIENCE_DAY_COUNT'),
('AIO_CONVENIENCE','AIO_MONTHLY'),
('AIO_CAFE','AIO_CAFE_PER_TX'),
('AIO_CAFE','AIO_CAFE_MONTH_COUNT'),
('AIO_CAFE','AIO_CAFE_DAY_COUNT'),
('AIO_CAFE','AIO_MONTHLY'),
('AIO_MEDICAL','AIO_MEDICAL_PER_TX'),
('AIO_MEDICAL','AIO_MEDICAL_MONTH_COUNT'),
('AIO_MEDICAL','AIO_MEDICAL_DAY_COUNT'),
('AIO_MEDICAL','AIO_MONTHLY'),
('AIO_SHOPPING','AIO_SHOPPING_PER_TX'),
('AIO_SHOPPING','AIO_SHOPPING_MONTH_COUNT'),
('AIO_SHOPPING','AIO_SHOPPING_DAY_COUNT'),
('AIO_SHOPPING','AIO_MONTHLY'),
('AIO_LEISURE','AIO_LEISURE_PER_TX'),
('AIO_LEISURE','AIO_LEISURE_MONTH_COUNT'),
('AIO_LEISURE','AIO_LEISURE_DAY_COUNT'),
('AIO_LEISURE','AIO_MONTHLY'),
('OUTBACK_INSTANT','OUTBACK_INSTANT_PER_TX'),
('OUTBACK_INSTANT','OUTBACK_INSTANT_MONTH_COUNT');
INSERT INTO benefit_limit_link(card_product_id,card_benefit_id,limit_group_id,created_at)
SELECT @ibk551_product,b.card_benefit_id,g.limit_group_id,UTC_TIMESTAMP(6) FROM ibk551_links s
JOIN card_benefit b ON b.card_product_id=@ibk551_product AND b.benefit_code=s.benefit AND b.policy_version=1
JOIN limit_group g ON g.card_product_id=@ibk551_product AND g.group_code=s.lim AND g.policy_version=1
WHERE NOT EXISTS(SELECT 1 FROM benefit_limit_link l WHERE l.card_benefit_id=b.card_benefit_id AND l.limit_group_id=g.limit_group_id);
CREATE TEMPORARY TABLE ibk551_targets(benefit VARCHAR(64),kind VARCHAR(16),value VARCHAR(128),excluded BOOLEAN);
INSERT INTO ibk551_targets VALUES
('GS25_INSTANT_10','BRAND','GS25',0),
('GS25_INSTANT_10','CONDITION','REGION:DOMESTIC',0),
('GS25_INSTANT_10','CONDITION','TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE',1),
('NARA_CONVENIENCE','BRAND','GS25',0),
('NARA_CONVENIENCE','BRAND','CU',0),
('NARA_CONVENIENCE','BRAND','EMART24',0),
('NARA_CONVENIENCE','CONDITION','REGION:DOMESTIC',0),
('NARA_CONVENIENCE','CONDITION','TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE',1),
('AIO_CONVENIENCE','BRAND','GS25',0),
('AIO_CONVENIENCE','BRAND','CU',0),
('AIO_CONVENIENCE','BRAND','EMART24',0),
('AIO_CONVENIENCE','CONDITION','REGION:DOMESTIC',0),
('AIO_CONVENIENCE','CONDITION','TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE',1),
('AIO_CAFE','BRAND','STARBUCKS',0),
('AIO_CAFE','BRAND','MEGA_MGC',0),
('AIO_CAFE','CONDITION','REGION:DOMESTIC',0),
('AIO_CAFE','CONDITION','TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE',1),
('AIO_MEDICAL','CATEGORY','HOSPITAL',0),
('AIO_MEDICAL','CATEGORY','DENTAL_CLINIC',0),
('AIO_MEDICAL','CATEGORY','KOREAN_MEDICINE_CLINIC',0),
('AIO_MEDICAL','CATEGORY','PHARMACY',0),
('AIO_MEDICAL','CONDITION','REGION:DOMESTIC',0),
('AIO_MEDICAL','CONDITION','TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE',1),
('AIO_SHOPPING','BRAND','COUPANG',0),
('AIO_SHOPPING','BRAND','MUSINSA',0),
('AIO_SHOPPING','BRAND','NAVER_PLUS_STORE',0),
('AIO_SHOPPING','BRAND','GMARKET',0),
('AIO_SHOPPING','BRAND','BAEMIN',0),
('AIO_SHOPPING','CONDITION','REGION:DOMESTIC',0),
('AIO_SHOPPING','CONDITION','TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE',1),
('AIO_SHOPPING','CONDITION','CHANNEL:ONLINE',0),
('AIO_LEISURE','BRAND','NOL',0),
('AIO_LEISURE','BRAND','SOCAR',0),
('AIO_LEISURE','CATEGORY','PC_ROOM',0),
('AIO_LEISURE','CONDITION','REGION:DOMESTIC',0),
('AIO_LEISURE','CONDITION','TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE',1),
('OUTBACK_INSTANT','BRAND','OUTBACK',0),
('OUTBACK_INSTANT','CONDITION','REGION:DOMESTIC',0),
('OUTBACK_INSTANT','CONDITION','TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE',1),
('OUTBACK_INSTANT','CONDITION','CHANNEL:OFFLINE',0);
INSERT INTO ibk551_guard SELECT 0 FROM ibk551_targets s WHERE s.kind='BRAND' AND NOT EXISTS(SELECT 1 FROM merchant m WHERE m.brand_code=s.value) LIMIT 1;
INSERT INTO benefit_target(card_benefit_id,target_type,merchant_id,target_value,is_excluded,created_at)
SELECT b.card_benefit_id,s.kind,m.merchant_id,IF(s.kind='BRAND',NULL,s.value),s.excluded,UTC_TIMESTAMP(6)
FROM ibk551_targets s JOIN card_benefit b ON b.card_product_id=@ibk551_product AND b.benefit_code=s.benefit AND b.policy_version=1
LEFT JOIN merchant m ON s.kind='BRAND' AND m.brand_code=s.value
WHERE NOT EXISTS(SELECT 1 FROM benefit_target t WHERE t.card_benefit_id=b.card_benefit_id AND t.target_type=s.kind
 AND (t.merchant_id <=> m.merchant_id) AND (t.target_value <=> IF(s.kind='BRAND',NULL,s.value)) AND t.is_excluded=s.excluded);
INSERT INTO benefit_target_group(card_benefit_id,group_code,is_excluded,created_at)
SELECT t.card_benefit_id,CONCAT('IBK_',t.benefit_target_id),0,UTC_TIMESTAMP(6) FROM benefit_target t JOIN card_benefit b USING(card_benefit_id)
WHERE b.card_product_id=@ibk551_product AND t.target_type<>'CONDITION'
 AND NOT EXISTS(SELECT 1 FROM benefit_target_group_member m WHERE m.benefit_target_id=t.benefit_target_id)
 AND NOT EXISTS(SELECT 1 FROM benefit_target_group g WHERE g.card_benefit_id=t.card_benefit_id AND g.group_code=CONCAT('IBK_',t.benefit_target_id));
INSERT INTO benefit_target_group_member(benefit_target_group_id,benefit_target_id,card_benefit_id)
SELECT g.benefit_target_group_id,t.benefit_target_id,t.card_benefit_id FROM benefit_target t JOIN card_benefit b USING(card_benefit_id)
JOIN benefit_target_group g ON g.card_benefit_id=t.card_benefit_id AND g.group_code=CONCAT('IBK_',t.benefit_target_id)
WHERE b.card_product_id=@ibk551_product AND t.target_type<>'CONDITION'
 AND NOT EXISTS(SELECT 1 FROM benefit_target_group_member m WHERE m.benefit_target_id=t.benefit_target_id);
INSERT INTO benefit_application_group(card_product_id,group_code,policy_version,application_mode)
SELECT @ibk551_product,'IBK_SERVICES',1,'STACK' WHERE NOT EXISTS(SELECT 1 FROM benefit_application_group WHERE card_product_id=@ibk551_product AND group_code='IBK_SERVICES' AND policy_version=1);
INSERT INTO ibk551_guard SELECT 0 FROM benefit_application_group WHERE card_product_id=@ibk551_product AND (group_code<>'IBK_SERVICES' OR policy_version<>1 OR application_mode<>'STACK') LIMIT 1;
INSERT INTO benefit_application_member(benefit_application_group_id,card_product_id,card_benefit_id,priority_order)
SELECT g.benefit_application_group_id,@ibk551_product,b.card_benefit_id,NULL FROM benefit_application_group g JOIN card_benefit b ON b.card_product_id=g.card_product_id
WHERE g.card_product_id=@ibk551_product AND g.group_code='IBK_SERVICES' AND b.policy_version=1
 AND NOT EXISTS(SELECT 1 FROM benefit_application_member m WHERE m.card_benefit_id=b.card_benefit_id);
DROP TEMPORARY TABLE ibk551_targets;
DROP TEMPORARY TABLE ibk551_links;
DROP TEMPORARY TABLE ibk551_tiers;
DROP TEMPORARY TABLE ibk551_limits;
DROP TEMPORARY TABLE ibk551_benefits;
DROP TEMPORARY TABLE ibk551_merchants;
DROP TEMPORARY TABLE ibk551_guard;
COMMIT;
