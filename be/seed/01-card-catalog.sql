-- S15P31E103-97 / LOCAL DEVELOPMENT ONLY / MySQL 8.4.11.
-- Products, benefit definitions, limits and verified target mappings.
-- CONDITION values are seed data; application interpretation is a separate implementation.
-- Transaction context must be verified by the server; missing facts are UNKNOWN.
-- Read README.md before connecting application code to these rows.
-- Execute as one batch, stop on the first error (never use mysql --force).
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TEMPORARY TABLE seed97_benefit (
    product_id BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(150) NOT NULL,
    rate DECIMAL(9,6) NOT NULL,
    min_payment BIGINT NOT NULL,
    min_performance BIGINT NOT NULL,
    conditions JSON NULL,
    daily_count BIGINT NULL,
    monthly_count BIGINT NULL,
    transaction_cap BIGINT NULL,
    monthly_cap BIGINT NULL,
    shared_group VARCHAR(64) NULL,
    terms_url VARCHAR(1000) NOT NULL
);
CREATE TEMPORARY TABLE seed97_limit (
    product_id BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(150) NOT NULL,
    period_type VARCHAR(16) NOT NULL,
    unit VARCHAR(16) NOT NULL,
    limit_value BIGINT NULL
);
CREATE TEMPORARY TABLE seed97_tier (
    product_id BIGINT NOT NULL,
    group_code VARCHAR(64) NOT NULL,
    lower_amount BIGINT NOT NULL,
    upper_amount BIGINT NULL,
    limit_value BIGINT NOT NULL
);

START TRANSACTION;
SET @seed97_now = UTC_TIMESTAMP(6);
SET @seed97_mr_url = 'https://www.shinhancard.com/pconts/html/card/apply/credit/1187937_2207.html';
SET @seed97_first_url = 'https://www.shinhancard.com/pconts/html/card/apply/credit/1227020_2207.html';
SET @seed97_zero_url = 'https://www.hyundaicard.com/cpc/cr/CPCCR0201_01.hc?cardWcd=ZROE3';

-- Issuer codes are local catalog identifiers, NOT SSAFY bank/product codes.
INSERT INTO card_product (issuer_code, product_name, card_type, terms_url, created_at, updated_at)
SELECT s.issuer, s.name, 'CREDIT', s.url, @seed97_now, @seed97_now
FROM (
    SELECT 'SHINHAN' AS issuer, '신한카드 Mr.Life' AS name, @seed97_mr_url AS url
    UNION ALL SELECT 'HYUNDAI', '현대카드 ZERO Edition3 (할인형)', @seed97_zero_url
    UNION ALL SELECT 'SHINHAN', '신한카드 처음', @seed97_first_url
) s
WHERE NOT EXISTS (
    SELECT 1 FROM card_product p
    WHERE p.issuer_code = s.issuer AND p.product_name = s.name AND p.card_type = 'CREDIT'
);

SET @seed97_mr = (SELECT card_product_id FROM card_product
    WHERE issuer_code = 'SHINHAN' AND product_name = '신한카드 Mr.Life' AND card_type = 'CREDIT');
SET @seed97_zero = (SELECT card_product_id FROM card_product
    WHERE issuer_code = 'HYUNDAI' AND product_name = '현대카드 ZERO Edition3 (할인형)' AND card_type = 'CREDIT');
SET @seed97_first = (SELECT card_product_id FROM card_product
    WHERE issuer_code = 'SHINHAN' AND product_name = '신한카드 처음' AND card_type = 'CREDIT');

-- Shared groups belong to a benefit service, not to the whole Mr.Life card.
-- Cheoeum TODAY groups are shared by all four '오늘도 5%' benefits.
INSERT INTO seed97_benefit VALUES
(@seed97_zero, 'BASE_DISCOUNT', '기본 청구 할인', 0.008, 0, 0,
 NULL, NULL, NULL, NULL, NULL, NULL, @seed97_zero_url),
(@seed97_mr, 'UTILITY', '월납 공과금 할인', 0.10, 0, 300000,
 NULL, 1, NULL, 5000, NULL, 'UTILITY_MONTHLY', @seed97_mr_url),
(@seed97_mr, 'CONVENIENCE', '편의점 할인', 0.10, 0, 300000,
 NULL, 1, 5, 1000, NULL, 'TIME_MONTHLY', @seed97_mr_url),
(@seed97_mr, 'HOSPITAL_PHARMACY', '병원·약국 할인', 0.10, 0, 300000,
 NULL, 1, 5, 1000, NULL, 'TIME_MONTHLY', @seed97_mr_url),
(@seed97_mr, 'LAUNDRY', '세탁소 할인', 0.10, 0, 300000,
 NULL, 1, 5, 1000, NULL, 'TIME_MONTHLY', @seed97_mr_url),
(@seed97_mr, 'NIGHT_SHOPPING', '야간 온라인쇼핑 할인', 0.10, 0, 300000,
 JSON_OBJECT('startTime', '21:00', 'endTime', '09:00'), 1, 10, 1000, NULL, 'TIME_MONTHLY', @seed97_mr_url),
(@seed97_mr, 'NIGHT_TAXI', '야간 택시 할인', 0.10, 0, 300000,
 JSON_OBJECT('startTime', '21:00', 'endTime', '09:00'), 1, 10, 1000, NULL, 'TIME_MONTHLY', @seed97_mr_url),
(@seed97_mr, 'NIGHT_FOOD', '야간 식음료 할인', 0.10, 0, 300000,
 JSON_OBJECT('startTime', '21:00', 'endTime', '09:00'), 1, 10, 1000, NULL, 'TIME_MONTHLY', @seed97_mr_url),
(@seed97_mr, 'WEEKEND_MART', '주말 할인마트 할인', 0.10, 0, 300000,
 JSON_OBJECT('weekdays', JSON_ARRAY('SAT', 'SUN')), 1, NULL, 5000, NULL, 'WEEKEND_MONTHLY', @seed97_mr_url),
(@seed97_first, 'TODAY_FOOD', '오늘도 음식점 5% 적립', 0.05, 0, 300000,
 NULL, NULL, NULL, 500, NULL, 'TODAY_MONTHLY', @seed97_first_url),
(@seed97_first, 'TODAY_CAFE', '오늘도 카페 5% 적립', 0.05, 0, 300000,
 NULL, NULL, NULL, 500, NULL, 'TODAY_MONTHLY', @seed97_first_url),
(@seed97_first, 'TODAY_CONVENIENCE', '오늘도 편의점 5% 적립', 0.05, 0, 300000,
 NULL, NULL, NULL, 500, NULL, 'TODAY_MONTHLY', @seed97_first_url),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', '오늘도 온라인쇼핑 5% 적립', 0.05, 0, 300000,
 NULL, NULL, NULL, 500, NULL, 'TODAY_MONTHLY', @seed97_first_url);

-- Unknown rounding/date/cutoff fields deliberately stay NULL.
-- Existing policy versions are never overwritten by rerunning this file.
INSERT INTO card_benefit (card_product_id, benefit_code, policy_version, benefit_name,
    benefit_type, calculation_type, rate, value_unit, point_krw_rate, application_timing,
    min_payment_amount, min_performance_amount, conditions_json, terms_url, created_at)
SELECT s.product_id, s.code, 1, s.name,
    IF(s.product_id = @seed97_first, 'POINT', 'DISCOUNT'), 'RATE', s.rate,
    IF(s.product_id = @seed97_first, 'POINT', 'KRW'),
    IF(s.product_id = @seed97_first, 1, NULL),
    IF(s.product_id = @seed97_first, 'POINT_ACCRUAL', 'AT_BILLING'),
    s.min_payment, s.min_performance, s.conditions, s.terms_url, @seed97_now
FROM seed97_benefit s
WHERE NOT EXISTS (SELECT 1 FROM card_benefit b
    WHERE b.card_product_id = s.product_id AND b.benefit_code = s.code AND b.policy_version = 1);

INSERT INTO seed97_limit VALUES
(@seed97_mr, 'UTILITY_MONTHLY', '월납 월 할인 한도', 'MONTHLY', 'KRW', NULL),
(@seed97_mr, 'TIME_MONTHLY', 'TIME 서비스 공유 월 할인 한도', 'MONTHLY', 'KRW', NULL),
(@seed97_mr, 'WEEKEND_MONTHLY', '주말 서비스 공유 월 할인 한도', 'MONTHLY', 'KRW', NULL),
(@seed97_first, 'TODAY_MONTHLY', '오늘도 5% 월 공유 적립 한도', 'MONTHLY', 'POINT', NULL),
(@seed97_first, 'TODAY_DAILY', '오늘도 5% 일 공유 적립 한도', 'DAILY', 'POINT', 1000);

INSERT INTO seed97_limit
SELECT product_id, CONCAT(code, '_DAILY_COUNT'), CONCAT(name, ' 일 횟수'), 'DAILY', 'COUNT', daily_count
FROM seed97_benefit WHERE daily_count IS NOT NULL;
INSERT INTO seed97_limit
SELECT product_id, CONCAT(code, '_MONTHLY_COUNT'), CONCAT(name, ' 월 횟수'), 'MONTHLY', 'COUNT', monthly_count
FROM seed97_benefit WHERE monthly_count IS NOT NULL;
INSERT INTO seed97_limit
SELECT product_id, CONCAT(code, '_PER_TRANSACTION'), CONCAT(name, ' 건별 혜택 한도'), 'PER_TRANSACTION', IF(product_id = @seed97_first, 'POINT', 'KRW'), transaction_cap
FROM seed97_benefit WHERE transaction_cap IS NOT NULL;
INSERT INTO seed97_limit
SELECT product_id, CONCAT(code, '_MONTHLY_AMOUNT'), CONCAT(name, ' 영역별 월 할인 한도'), 'MONTHLY', 'KRW', monthly_cap
FROM seed97_benefit WHERE monthly_cap IS NOT NULL;

INSERT INTO limit_group (card_product_id, group_code, policy_version, group_name,
    period_type, limit_unit, base_limit_value, created_at)
SELECT s.product_id, s.code, 1, s.name, s.period_type, s.unit, s.limit_value, @seed97_now
FROM seed97_limit s
WHERE NOT EXISTS (SELECT 1 FROM limit_group g
    WHERE g.card_product_id = s.product_id AND g.group_code = s.code AND g.policy_version = 1);

INSERT INTO benefit_limit_link (card_product_id, card_benefit_id, limit_group_id, created_at)
SELECT s.product_id, b.card_benefit_id, g.limit_group_id, @seed97_now
FROM seed97_benefit s
JOIN card_benefit b ON b.card_product_id = s.product_id AND b.benefit_code = s.code AND b.policy_version = 1
JOIN limit_group g ON g.card_product_id = s.product_id AND g.policy_version = 1
    AND (g.group_code = s.shared_group
        OR (s.product_id = @seed97_first AND g.group_code = 'TODAY_DAILY')
        OR (s.daily_count IS NOT NULL AND g.group_code = CONCAT(s.code, '_DAILY_COUNT'))
        OR (s.monthly_count IS NOT NULL AND g.group_code = CONCAT(s.code, '_MONTHLY_COUNT'))
        OR (s.transaction_cap IS NOT NULL AND g.group_code = CONCAT(s.code, '_PER_TRANSACTION'))
        OR (s.monthly_cap IS NOT NULL AND g.group_code = CONCAT(s.code, '_MONTHLY_AMOUNT')))
WHERE NOT EXISTS (SELECT 1 FROM benefit_limit_link l
    WHERE l.card_benefit_id = b.card_benefit_id AND l.limit_group_id = g.limit_group_id);

INSERT INTO seed97_tier VALUES
(@seed97_mr, 'UTILITY_MONTHLY', 300000, 500000, 3000),
(@seed97_mr, 'UTILITY_MONTHLY', 500000, 1000000, 7000),
(@seed97_mr, 'UTILITY_MONTHLY', 1000000, NULL, 10000),
(@seed97_mr, 'TIME_MONTHLY', 300000, 500000, 10000),
(@seed97_mr, 'TIME_MONTHLY', 500000, 1000000, 20000),
(@seed97_mr, 'TIME_MONTHLY', 1000000, NULL, 30000),
(@seed97_mr, 'WEEKEND_MONTHLY', 300000, 500000, 3000),
(@seed97_mr, 'WEEKEND_MONTHLY', 500000, 1000000, 7000),
(@seed97_mr, 'WEEKEND_MONTHLY', 1000000, NULL, 10000),
(@seed97_first, 'TODAY_MONTHLY', 300000, 500000, 5000),
(@seed97_first, 'TODAY_MONTHLY', 500000, 1000000, 10000),
(@seed97_first, 'TODAY_MONTHLY', 1000000, NULL, 20000);

INSERT INTO tier_limit (limit_group_id, min_performance_amount, max_performance_amount, limit_value, created_at)
SELECT g.limit_group_id, s.lower_amount, s.upper_amount, s.limit_value, @seed97_now
FROM seed97_tier s
JOIN limit_group g ON g.card_product_id = s.product_id AND g.group_code = s.group_code AND g.policy_version = 1
WHERE NOT EXISTS (SELECT 1 FROM tier_limit t
    WHERE t.limit_group_id = g.limit_group_id AND t.min_performance_amount = s.lower_amount);

-- V2 industry names: merchant_category_code and CATEGORY.target_value use the
-- same literal codes. No V1 hierarchy/prefix matching is assumed by this seed.
CREATE TEMPORARY TABLE seed97_merchant (
    brand_code VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    category_code VARCHAR(64) NOT NULL
);
INSERT INTO seed97_merchant VALUES
('GS25', 'GS25', 'CONVENIENCE_STORE'),
('CU', 'CU', 'CONVENIENCE_STORE'),
('STARBUCKS', '스타벅스', 'COFFEE_SHOP'),
('BLUE_BOTTLE', '블루보틀', 'COFFEE_SHOP'),
('ANGELINUS', '엔제리너스', 'COFFEE_SHOP'),
('EDIYA', '이디야', 'COFFEE_SHOP'),
('PAUL_BASSETT', '폴바셋', 'COFFEE_SHOP'),
('TWOSOME_PLACE', '투썸플레이스', 'COFFEE_SHOP'),
('PAIKS_COFFEE', '빽다방', 'COFFEE_SHOP'),
('EMART24', '이마트24', 'CONVENIENCE_STORE'),
('SEVEN_ELEVEN', '세븐일레븐', 'CONVENIENCE_STORE'),
('KURLY', '컬리', 'ONLINE_MARKETPLACE'),
('YAMU_TEST_CAFE', '야무 테스트 카페', 'COFFEE_SHOP'),
('AUCTION', '옥션', 'ONLINE_MARKETPLACE'),
('GMARKET', 'G마켓', 'ONLINE_MARKETPLACE'),
('AK_MALL', 'AK몰', 'ONLINE_MARKETPLACE'),
('ELEVEN_STREET', '11번가', 'ONLINE_MARKETPLACE'),
('TMON', '티켓몬스터', 'ONLINE_MARKETPLACE'),
('LOTTE_HOME_SHOPPING', '롯데홈쇼핑', 'ONLINE_MARKETPLACE'),
('COUPANG', '쿠팡', 'ONLINE_MARKETPLACE'),
('EMART_EVERYDAY', '이마트 에브리데이', 'SUPERMARKET'),
('LOTTE_SUPER', '롯데슈퍼', 'SUPERMARKET'),
('HOMEPLUS_EXPRESS', '홈플러스 익스프레스', 'SUPERMARKET'),
('EMART', '이마트', 'HYPERMARKET'),
('LOTTE_MART', '롯데마트', 'HYPERMARKET'),
('HOMEPLUS', '홈플러스', 'HYPERMARKET'),
('SKT', 'SKT', 'TELECOMMUNICATION'),
('KT', 'KT', 'TELECOMMUNICATION'),
('LG_UPLUS', 'LG U+', 'TELECOMMUNICATION'),
('YAMU_TEST_RESTAURANT', '야무 테스트 식당', 'GENERAL_RESTAURANT');

-- Refuse an existing brand with a different industry instead of changing it.
CREATE TEMPORARY TABLE seed97_guard (ok INT NOT NULL CHECK (ok = 1));
INSERT INTO seed97_guard
SELECT 0 FROM seed97_merchant s JOIN merchant m ON m.brand_code = s.brand_code
WHERE m.merchant_category_code <> s.category_code;

INSERT INTO merchant (merchant_name, brand_code, merchant_category_code, created_at, updated_at)
SELECT s.name, s.brand_code, s.category_code, @seed97_now, @seed97_now
FROM seed97_merchant s
WHERE NOT EXISTS (SELECT 1 FROM merchant m WHERE m.brand_code = s.brand_code);

CREATE TEMPORARY TABLE seed97_target (
    product_id BIGINT NOT NULL,
    benefit_code VARCHAR(64) NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_value VARCHAR(128) NULL,
    brand_code VARCHAR(64) NULL,
    is_excluded BOOLEAN NOT NULL
);

-- Four medical categories share ONE HOSPITAL_PHARMACY benefit and its limits.
INSERT INTO seed97_target VALUES
(@seed97_mr, 'CONVENIENCE', 'CATEGORY', 'CONVENIENCE_STORE', NULL, FALSE),
(@seed97_mr, 'HOSPITAL_PHARMACY', 'CATEGORY', 'HOSPITAL', NULL, FALSE),
(@seed97_mr, 'HOSPITAL_PHARMACY', 'CATEGORY', 'DENTAL_CLINIC', NULL, FALSE),
(@seed97_mr, 'HOSPITAL_PHARMACY', 'CATEGORY', 'KOREAN_MEDICINE_CLINIC', NULL, FALSE),
(@seed97_mr, 'HOSPITAL_PHARMACY', 'CATEGORY', 'PHARMACY', NULL, FALSE),
(@seed97_mr, 'HOSPITAL_PHARMACY', 'CATEGORY', 'VETERINARY_CLINIC', NULL, TRUE),
(@seed97_mr, 'LAUNDRY', 'CATEGORY', 'LAUNDRY', NULL, FALSE),
(@seed97_mr, 'NIGHT_TAXI', 'CATEGORY', 'TAXI', NULL, FALSE),
(@seed97_mr, 'NIGHT_FOOD', 'CATEGORY', 'KOREAN_RESTAURANT', NULL, FALSE),
(@seed97_mr, 'NIGHT_FOOD', 'CATEGORY', 'WESTERN_RESTAURANT', NULL, FALSE),
(@seed97_mr, 'NIGHT_FOOD', 'CATEGORY', 'JAPANESE_RESTAURANT', NULL, FALSE),
(@seed97_mr, 'NIGHT_FOOD', 'CATEGORY', 'CHINESE_RESTAURANT', NULL, FALSE),
(@seed97_mr, 'NIGHT_FOOD', 'CATEGORY', 'BUFFET_RESTAURANT', NULL, FALSE),
(@seed97_mr, 'NIGHT_FOOD', 'CATEGORY', 'GENERAL_RESTAURANT', NULL, FALSE),
(@seed97_mr, 'NIGHT_FOOD', 'CATEGORY', 'FAST_FOOD', NULL, FALSE),
(@seed97_mr, 'NIGHT_FOOD', 'CATEGORY', 'COFFEE_SHOP', NULL, FALSE);

-- Monthly bills: retailer identity alone must not also discount device purchases.
INSERT INTO seed97_target VALUES
(@seed97_mr, 'UTILITY', 'CATEGORY', 'ELECTRIC_UTILITY', NULL, FALSE),
(@seed97_mr, 'UTILITY', 'CATEGORY', 'CITY_GAS_UTILITY', NULL, FALSE),
(@seed97_mr, 'UTILITY', 'BRAND', NULL, 'SKT', FALSE),
(@seed97_mr, 'UTILITY', 'BRAND', NULL, 'KT', FALSE),
(@seed97_mr, 'UTILITY', 'BRAND', NULL, 'LG_UPLUS', FALSE),
(@seed97_mr, 'UTILITY', 'CONDITION', 'RULE:UTILITY_PAYMENT', NULL, FALSE),
(@seed97_mr, 'NIGHT_SHOPPING', 'BRAND', NULL, 'AUCTION', FALSE),
(@seed97_mr, 'NIGHT_SHOPPING', 'BRAND', NULL, 'GMARKET', FALSE),
(@seed97_mr, 'NIGHT_SHOPPING', 'BRAND', NULL, 'AK_MALL', FALSE),
(@seed97_mr, 'NIGHT_SHOPPING', 'BRAND', NULL, 'ELEVEN_STREET', FALSE),
(@seed97_mr, 'NIGHT_SHOPPING', 'BRAND', NULL, 'TMON', FALSE),
(@seed97_mr, 'NIGHT_SHOPPING', 'BRAND', NULL, 'LOTTE_HOME_SHOPPING', FALSE),
(@seed97_mr, 'NIGHT_SHOPPING', 'BRAND', NULL, 'COUPANG', FALSE),
(@seed97_mr, 'NIGHT_SHOPPING', 'CONDITION', 'CHANNEL:ONLINE', NULL, FALSE),
(@seed97_mr, 'NIGHT_SHOPPING', 'CONDITION', 'RULE:MR_LIFE_SHOPPING_ROUTE', NULL, FALSE),
(@seed97_mr, 'WEEKEND_MART', 'BRAND', NULL, 'EMART', FALSE),
(@seed97_mr, 'WEEKEND_MART', 'BRAND', NULL, 'LOTTE_MART', FALSE),
(@seed97_mr, 'WEEKEND_MART', 'BRAND', NULL, 'HOMEPLUS', FALSE),
(@seed97_mr, 'WEEKEND_MART', 'BRAND', NULL, 'EMART_EVERYDAY', FALSE),
(@seed97_mr, 'WEEKEND_MART', 'BRAND', NULL, 'LOTTE_SUPER', FALSE),
(@seed97_mr, 'WEEKEND_MART', 'BRAND', NULL, 'HOMEPLUS_EXPRESS', FALSE),
(@seed97_mr, 'WEEKEND_MART', 'CONDITION', 'TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE', NULL, TRUE),
(@seed97_zero, 'BASE_DISCOUNT', 'ALL', NULL, NULL, FALSE),
(@seed97_zero, 'BASE_DISCOUNT', 'CONDITION', 'RULE:OVERSEAS_ELIGIBILITY', NULL, FALSE),
(@seed97_zero, 'BASE_DISCOUNT', 'CONDITION', 'INSTALLMENT:INTEREST_FREE', NULL, TRUE),
(@seed97_zero, 'BASE_DISCOUNT', 'CONDITION', 'ISSUER_DISCOUNT:APPLIED', NULL, TRUE);

-- ZERO's payment exclusions are transaction facts, never whole-store exclusions.
INSERT INTO seed97_target
SELECT @seed97_zero, 'BASE_DISCOUNT', 'CONDITION', CONCAT('TRANSACTION_KIND:', s.kind), NULL, TRUE
FROM (
    SELECT 'LONG_TERM_CARD_LOAN' AS kind UNION ALL SELECT 'SHORT_TERM_CARD_LOAN'
    UNION ALL SELECT 'ANNUAL_FEE' UNION ALL SELECT 'FEE' UNION ALL SELECT 'INTEREST'
    UNION ALL SELECT 'OVERSEAS_FEE' UNION ALL SELECT 'TAX_OR_PUBLIC_CHARGE'
    UNION ALL SELECT 'ELECTRICITY_BILL' UNION ALL SELECT 'CITY_GAS_BILL'
    UNION ALL SELECT 'APARTMENT_MAINTENANCE' UNION ALL SELECT 'EDUCATION_PAYMENT'
    UNION ALL SELECT 'SOCIAL_INSURANCE' UNION ALL SELECT 'GIFT_CERTIFICATE_PURCHASE'
    UNION ALL SELECT 'PREPAID_PURCHASE' UNION ALL SELECT 'PREPAID_TOPUP'
    UNION ALL SELECT 'HIGHWAY_TOLL' UNION ALL SELECT 'POSTPAID_HIPASS' UNION ALL SELECT 'EXPRESS_BUS'
) s;

-- Cheoeum scope: only '오늘도 5%'. Excludes life/travel/fashion/recurring/bonuses.
-- 10,000 KRW eligible spend x 5% = 500P per transaction; daily/monthly limits are POINT.
-- New CONDITION codes below are seed policy data, not a benefit-calculation implementation.
-- RULE:CHEOEUM_CAFE_ROUTE = verified OFFLINE, or verified SMART_ORDER at STARBUCKS/TWOSOME_PLACE.
-- RULE:OFFICIAL_PURCHASE_ROUTE = direct official merchant website/app (existing seed code).
-- RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE = subtract redeemed point portion from eligible spend;
-- it does NOT exclude the whole mixed-payment transaction. The factual amount must be supplied by the server.
INSERT INTO seed97_target VALUES
(@seed97_first, 'TODAY_FOOD', 'CATEGORY', 'KOREAN_RESTAURANT', NULL, FALSE),
(@seed97_first, 'TODAY_FOOD', 'CATEGORY', 'CHINESE_RESTAURANT', NULL, FALSE),
(@seed97_first, 'TODAY_FOOD', 'CATEGORY', 'JAPANESE_RESTAURANT', NULL, FALSE),
(@seed97_first, 'TODAY_FOOD', 'CATEGORY', 'WESTERN_RESTAURANT', NULL, FALSE),
(@seed97_first, 'TODAY_FOOD', 'CATEGORY', 'GENERAL_RESTAURANT', NULL, FALSE),
(@seed97_first, 'TODAY_FOOD', 'CATEGORY', 'FAST_FOOD', NULL, FALSE),
(@seed97_first, 'TODAY_FOOD', 'CATEGORY', 'BUFFET_RESTAURANT', NULL, FALSE),
(@seed97_first, 'TODAY_CAFE', 'CATEGORY', 'COFFEE_SHOP', NULL, FALSE),
(@seed97_first, 'TODAY_CAFE', 'BRAND', NULL, 'STARBUCKS', FALSE),
(@seed97_first, 'TODAY_CAFE', 'BRAND', NULL, 'BLUE_BOTTLE', FALSE),
(@seed97_first, 'TODAY_CAFE', 'BRAND', NULL, 'ANGELINUS', FALSE),
(@seed97_first, 'TODAY_CAFE', 'BRAND', NULL, 'EDIYA', FALSE),
(@seed97_first, 'TODAY_CAFE', 'BRAND', NULL, 'PAUL_BASSETT', FALSE),
(@seed97_first, 'TODAY_CAFE', 'BRAND', NULL, 'TWOSOME_PLACE', FALSE),
(@seed97_first, 'TODAY_CAFE', 'BRAND', NULL, 'PAIKS_COFFEE', FALSE),
(@seed97_first, 'TODAY_CONVENIENCE', 'BRAND', NULL, 'CU', FALSE),
(@seed97_first, 'TODAY_CONVENIENCE', 'BRAND', NULL, 'GS25', FALSE),
(@seed97_first, 'TODAY_CONVENIENCE', 'BRAND', NULL, 'EMART24', FALSE),
(@seed97_first, 'TODAY_CONVENIENCE', 'BRAND', NULL, 'SEVEN_ELEVEN', FALSE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'BRAND', NULL, 'COUPANG', FALSE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'BRAND', NULL, 'KURLY', FALSE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'CHANNEL:OFFLINE', NULL, FALSE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'CHANNEL:OFFLINE', NULL, FALSE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'RULE:CHEOEUM_CAFE_ROUTE', NULL, FALSE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'CHANNEL:ONLINE', NULL, FALSE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'RULE:OFFICIAL_PURCHASE_ROUTE', NULL, FALSE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'FACILITY:DEPARTMENT_STORE', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'FACILITY:DISCOUNT_STORE', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'FACILITY:DUTY_FREE', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'FACILITY:AIRPORT', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'FACILITY:TRAIN_STATION', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'FACILITY:DEPARTMENT_STORE', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'FACILITY:DISCOUNT_STORE', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'FACILITY:DUTY_FREE', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'FACILITY:AIRPORT', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'FACILITY:TRAIN_STATION', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'FACILITY:DEPARTMENT_STORE', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'FACILITY:DISCOUNT_STORE', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'FACILITY:DUTY_FREE', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'FACILITY:AIRPORT', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'FACILITY:TRAIN_STATION', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE', NULL, FALSE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:LONG_TERM_CARD_LOAN', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:SHORT_TERM_CARD_LOAN', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:ANNUAL_FEE', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:FEE', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:INTEREST', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:PREPAID_PURCHASE', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:PREPAID_TOPUP', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:TAX_OR_PUBLIC_CHARGE', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:SOCIAL_INSURANCE', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:ELECTRICITY_BILL', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:CITY_GAS_BILL', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:APARTMENT_MAINTENANCE', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:EDUCATION_PAYMENT', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:CANCELED', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'TRANSACTION_KIND:DELIVERY_APP', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'INSTALLMENT:INTEREST_FREE', NULL, TRUE),
(@seed97_first, 'TODAY_FOOD', 'CONDITION', 'ISSUER_DISCOUNT:APPLIED', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE', NULL, FALSE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:LONG_TERM_CARD_LOAN', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:SHORT_TERM_CARD_LOAN', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:ANNUAL_FEE', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:FEE', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:INTEREST', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:PREPAID_PURCHASE', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:PREPAID_TOPUP', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:TAX_OR_PUBLIC_CHARGE', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:SOCIAL_INSURANCE', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:ELECTRICITY_BILL', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:CITY_GAS_BILL', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:APARTMENT_MAINTENANCE', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:EDUCATION_PAYMENT', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:CANCELED', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'TRANSACTION_KIND:DELIVERY_APP', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'INSTALLMENT:INTEREST_FREE', NULL, TRUE),
(@seed97_first, 'TODAY_CAFE', 'CONDITION', 'ISSUER_DISCOUNT:APPLIED', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE', NULL, FALSE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:LONG_TERM_CARD_LOAN', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:SHORT_TERM_CARD_LOAN', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:ANNUAL_FEE', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:FEE', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:INTEREST', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:PREPAID_PURCHASE', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:PREPAID_TOPUP', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:TAX_OR_PUBLIC_CHARGE', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:SOCIAL_INSURANCE', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:ELECTRICITY_BILL', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:CITY_GAS_BILL', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:APARTMENT_MAINTENANCE', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:EDUCATION_PAYMENT', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:CANCELED', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'TRANSACTION_KIND:DELIVERY_APP', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'INSTALLMENT:INTEREST_FREE', NULL, TRUE),
(@seed97_first, 'TODAY_CONVENIENCE', 'CONDITION', 'ISSUER_DISCOUNT:APPLIED', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'RULE:EXCLUDE_REDEEMED_POINTS_FROM_BASE', NULL, FALSE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:LONG_TERM_CARD_LOAN', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:SHORT_TERM_CARD_LOAN', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:ANNUAL_FEE', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:FEE', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:INTEREST', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:PREPAID_PURCHASE', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:PREPAID_TOPUP', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:TAX_OR_PUBLIC_CHARGE', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:SOCIAL_INSURANCE', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:ELECTRICITY_BILL', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:CITY_GAS_BILL', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:APARTMENT_MAINTENANCE', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:EDUCATION_PAYMENT', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:CANCELED', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:DELIVERY_APP', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'INSTALLMENT:INTEREST_FREE', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'ISSUER_DISCOUNT:APPLIED', NULL, TRUE),
(@seed97_first, 'TODAY_ONLINE_SHOPPING', 'CONDITION', 'TRANSACTION_KIND:MEMBERSHIP_FEE', NULL, TRUE);

-- 2026-11-09 approved mock scope: six named brands; no additional online mall.
-- Repair existing catalogs in the same transaction. Remove the obsolete legacy category path first.
DELETE gm FROM benefit_target_group_member gm
JOIN benefit_target_group g ON g.benefit_target_group_id = gm.benefit_target_group_id
JOIN card_benefit b ON b.card_benefit_id = g.card_benefit_id
WHERE b.card_product_id = @seed97_mr AND b.benefit_code = 'WEEKEND_MART' AND b.policy_version = 1
 AND g.group_code = CONCAT('LEGACY_0_',LEFT(SHA2('CATEGORY:SUPERMARKET',256),40));
DELETE g FROM benefit_target_group g JOIN card_benefit b ON b.card_benefit_id = g.card_benefit_id
WHERE b.card_product_id = @seed97_mr AND b.benefit_code = 'WEEKEND_MART' AND b.policy_version = 1
 AND g.group_code = CONCAT('LEGACY_0_',LEFT(SHA2('CATEGORY:SUPERMARKET',256),40));
DELETE t FROM benefit_target t JOIN card_benefit b ON b.card_benefit_id = t.card_benefit_id
WHERE b.card_product_id = @seed97_mr AND b.benefit_code = 'WEEKEND_MART' AND b.policy_version = 1 AND t.is_excluded = FALSE
 AND ((t.target_type = 'CATEGORY' AND t.target_value = 'SUPERMARKET')
   OR (t.target_type = 'CONDITION' AND t.target_value = 'RULE:MR_LIFE_MART_FAMILY'));

-- Missing references are an error, not an invitation to silently omit a rule.
INSERT INTO seed97_guard
SELECT 0 FROM seed97_target s
LEFT JOIN card_benefit b ON b.card_product_id = s.product_id
    AND b.benefit_code = s.benefit_code AND b.policy_version = 1
LEFT JOIN merchant m ON m.brand_code = s.brand_code
WHERE b.card_benefit_id IS NULL OR (s.target_type = 'BRAND' AND m.merchant_id IS NULL);

INSERT INTO benefit_target (card_benefit_id, target_type, merchant_id, target_value, is_excluded, created_at)
SELECT DISTINCT b.card_benefit_id, s.target_type, m.merchant_id, s.target_value, IF(s.target_type='CONDITION',s.is_excluded,0), @seed97_now
FROM seed97_target s
JOIN card_benefit b ON b.card_product_id = s.product_id
    AND b.benefit_code = s.benefit_code AND b.policy_version = 1
LEFT JOIN merchant m ON m.brand_code = s.brand_code
WHERE NOT EXISTS (
    SELECT 1 FROM benefit_target t
    WHERE t.card_benefit_id = b.card_benefit_id AND t.target_type = s.target_type
      AND t.is_excluded = IF(s.target_type='CONDITION',s.is_excluded,0)
      AND t.merchant_id <=> m.merchant_id AND t.target_value <=> s.target_value
);
-- target groups generated; CONDITION stays common, not a group member.
INSERT INTO benefit_target_group(card_benefit_id,group_code,is_excluded,created_at)
SELECT DISTINCT b.card_benefit_id, CONCAT('LEGACY_',s.is_excluded,'_',LEFT(SHA2(CONCAT(s.target_type,':',COALESCE(s.brand_code,s.target_value,'*')),256),40)), s.is_excluded, @seed97_now
FROM seed97_target s
JOIN card_benefit b ON b.card_product_id = s.product_id
    AND b.benefit_code = s.benefit_code AND b.policy_version = 1
LEFT JOIN merchant m ON m.brand_code = s.brand_code

WHERE s.target_type <> 'CONDITION' AND NOT EXISTS (
 SELECT 1 FROM benefit_target_group g WHERE g.card_benefit_id=b.card_benefit_id AND g.group_code=CONCAT('LEGACY_',s.is_excluded,'_',LEFT(SHA2(CONCAT(s.target_type,':',COALESCE(s.brand_code,s.target_value,'*')),256),40)));
INSERT INTO benefit_target_group_member(benefit_target_group_id,benefit_target_id,card_benefit_id)
SELECT DISTINCT g.benefit_target_group_id,t.benefit_target_id,b.card_benefit_id
FROM seed97_target s
JOIN card_benefit b ON b.card_product_id = s.product_id
    AND b.benefit_code = s.benefit_code AND b.policy_version = 1
LEFT JOIN merchant m ON m.brand_code = s.brand_code

JOIN benefit_target_group g ON g.card_benefit_id=b.card_benefit_id AND g.group_code=CONCAT('LEGACY_',s.is_excluded,'_',LEFT(SHA2(CONCAT(s.target_type,':',COALESCE(s.brand_code,s.target_value,'*')),256),40))
JOIN benefit_target t ON t.card_benefit_id=b.card_benefit_id AND t.target_type=s.target_type
 AND t.merchant_id <=> m.merchant_id AND t.target_value <=> s.target_value AND t.is_excluded=0
WHERE s.target_type <> 'CONDITION' AND NOT EXISTS (
 SELECT 1 FROM benefit_target_group_member x WHERE x.benefit_target_group_id=g.benefit_target_group_id AND x.benefit_target_id=t.benefit_target_id);


COMMIT;
DROP TEMPORARY TABLE seed97_target;
DROP TEMPORARY TABLE seed97_guard;
DROP TEMPORARY TABLE seed97_merchant;
DROP TEMPORARY TABLE seed97_tier;
DROP TEMPORARY TABLE seed97_limit;
DROP TEMPORARY TABLE seed97_benefit;
