// A/B 카드 20종 통합자료 v2(data/*.csv)를 DB 표 모양 CSV · 제외 보고서 · 적재 SQL로 바꾼다. S15P31E103-178
// node be/seed/catalog-ab20/build-seed.mjs <v2 압축을 푼 폴더>
// - 97 시드(01-card-catalog.sql)에 있는 3장은 건너뛴다. 브랜드 업종은 97 값을 먼저 따른다.
// - 업종 · 조건 코드는 임시 사전(codes/)을 쓴다. POL-004 확정 전이라 모두 '임시'다.
// - 같은 입력이면 같은 출력이 나온다(행 순서 고정, 날짜 · 난수 없음).
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const here = dirname(fileURLToPath(import.meta.url));
const src = process.argv[2];
if (!src || !existsSync(join(src, 'data', 'cards.csv'))) {
  console.log('사용: node be/seed/catalog-ab20/build-seed.mjs <YAMU-AB-카드20종-통합자료-v2 폴더>');
  process.exit(1);
}

// ── CSV ──
const BOM = '﻿';
function parseCsv(text) {
  if (text.startsWith(BOM)) text = text.slice(1);
  const rows = []; let row = [], cell = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { cell += '"'; i++; } else if (c === '"') quoted = false; else cell += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { row.push(cell); cell = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(cell); cell = '';
      if (row.length > 1 || row[0] !== '') rows.push(row);
      row = [];
    } else cell += c;
  }
  if (cell !== '' || row.length) { row.push(cell); rows.push(row); }
  return rows;
}
const readCsv = (file) => {
  const [head, ...body] = parseCsv(readFileSync(file, 'utf8'));
  return body.map(r => Object.fromEntries(head.map((k, i) => [k, r[i] ?? ''])));
};
const csvCell = (v) => { const s = v == null ? '' : String(v); return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
const writeCsv = (file, cols, rows) => {
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, BOM + [cols.join(','), ...rows.map(r => cols.map(c => csvCell(r[c])).join(','))].join('\n') + '\n');
};

// ── 입력 ──
const data = (n) => readCsv(join(src, 'data', `${n}.csv`));
const cards = data('cards'), sources = data('sources'), benefits = data('benefits'), targets = data('targets');
const conditions = data('conditions'), limits = data('limits'), links = data('limit_links');
const refMerchants = data('ref_merchants');
const predicates = readCsv(join(src, 'reports', 'A-predicate_dictionary.csv'));
const logic = readCsv(join(src, 'reports', 'A-condition_logic.csv'));
const unverified = readCsv(join(src, 'reports', '미확인-충돌-통합.csv'));
const categoryDict = new Map(readCsv(join(here, 'codes', 'merchant-categories.csv')).map(r => [r.cherry_code, r]));
const brandDict = new Map(readCsv(join(here, 'codes', 'merchant-brands.csv')).map(r => [r.merchant_key, r]));

// 97 시드의 브랜드 업종(같은 brand_code를 다른 업종으로 넣으면 97 guard가 적재를 멈춘다)
const seed97 = readFileSync(join(here, '..', '01-card-catalog.sql'), 'utf8');
const seed97Brands = new Map([...seed97.matchAll(/^\('([A-Z_0-9]+)', '([^']+)', '([A-Z_]+)'\)/gm)].map(m => [m[1], { name: m[2], category: m[3] }]));
const SEED97_CARDS = new Set(['shinhan-mrlife', 'hyundai-zero-edition3-discount', 'shinhan-cheoeum']);
const ISSUER = { '현대카드': 'HYUNDAI', '신한카드': 'SHINHAN', '삼성카드': 'SAMSUNG', 'KB국민카드': 'KB', '하나카드': 'HANA', '롯데카드': 'LOTTE', '카카오뱅크': 'KAKAOBANK' };

const upper = (s) => String(s).toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '');
const excluded = [], assumptions = [];
const exclude = (table, card, key, reason, detail = '') => excluded.push({ table, card_id: card, key, reason, detail });
const assume = (card, key, item, value, reason) => assumptions.push({ card_id: card, key, item, value, reason });

// ── 카드 ──
const products = [];
for (const c of cards) {
  if (SEED97_CARDS.has(c.card_id)) { exclude('cards', c.card_id, '', '97 시드에 이미 있음', '경린 님 01-card-catalog.sql 그대로 둠'); continue; }
  // 임시: MR !41 리뷰 후 팀 승인. 월별 대체 한도 지원 전까지 카드 전체를 보류한다.
  if (c.card_id === 'shinhan-pointplan') {
    const reason = 'Point Plan 카드 전체 적재 보류';
    const detail = '일반월과 5·12월 대체 한도를 선택하는 스키마·BE 계약 확정 후 재검토';
    exclude('cards', c.card_id, '', reason, detail);
    for (const b of benefits.filter(b => b.card_id === c.card_id)) exclude('benefits', c.card_id, b.benefit_key, reason, detail);
    continue;
  }
  const issuer = ISSUER[c.issuer];
  if (!issuer) throw new Error(`카드사 코드 없음: ${c.issuer}`);
  const page = sources.find(s => s.card_id === c.card_id && s.kind === 'PRODUCT_PAGE' && s.url);
  products.push({ card_id: c.card_id, issuer_code: issuer, product_name: c.product_name, card_type: c.card_type, terms_url: page ? page.url : '' });
}
const productOf = new Map(products.map(p => [p.card_id, p]));

// ── 조건 코드 사전(임시) ──
const conditionCodes = new Map(); // yamu_code → { from, raw, basis }
const noteCode = (code, from, raw, basis) => { if (!conditionCodes.has(code)) conditionCodes.set(code, { yamu_code: code, from, raw, basis }); return code; };
const predicateKind = new Map(predicates.map(p => [p.code, p.kind]));
// B가 만든 조건 값 중 업종·시설이 아니라 결제 경로를 뜻하는 것은 RULE로 둔다
const B_RULES = new Set(['NON_OFFICIAL_CHANNEL', 'AGENCY_SITE', 'LINKED_SITE_PAYMENT', 'APP_ORDER_OR_RESERVATION', 'DIRECT_RESTAURANT_PAYMENT',
  'NON_PARTNER_EASY_PAY', 'OWN_MERCHANT_NUMBER', 'TAXI_ONLINE_AUTO_PAYMENT', 'SPEEDMATE_ONLINE_PAYMENT', 'HANAPAY_WONDER_MATZIP']);
const B_FACILITY = { FACILITY_DEPARTMENT_STORE: 'DEPARTMENT_STORE', FACILITY_TENANT: 'LARGE_FACILITY_TENANT', LARGE_FACILITY_TENANT: 'LARGE_FACILITY_TENANT', BUILDING_TENANT: 'BUILDING_TENANT', MALL_TENANT_STORE: 'MALL_TENANT' };
function conditionValueCode(raw, from) {
  const kind = predicateKind.get(raw);
  if (kind === 'PERFORMANCE_FACT') return { fail: `실적 사실(${raw})은 거래 조건으로 쓰지 않음` };
  if (kind === 'TRANSACTION_FACT') return { code: noteCode(`TRANSACTION_KIND:${raw}`, from, raw, 'A 원자 사전 TRANSACTION_FACT') };
  if (kind === 'BOOLEAN_FACT') return { code: noteCode(`RULE:${raw}`, from, raw, 'A 원자 사전 BOOLEAN_FACT') };
  if (B_FACILITY[raw]) return { code: noteCode(`FACILITY:${B_FACILITY[raw]}`, from, raw, 'B 코드, 입점 시설') };
  if (B_RULES.has(raw)) return { code: noteCode(`RULE:${raw}`, from, raw, 'B 코드, 결제 경로') };
  if (!/^[A-Z][A-Z0-9_]*$/.test(raw)) return { fail: `조건 값 형식을 모름(${raw})` };
  return { code: noteCode(`TRANSACTION_KIND:${raw}`, from, raw, 'B 코드, 거래 종류') };
}

// 조건 행 → 최소 결제액 · conditions_json · 대상(CONDITION) 중 하나. 표현할 수 없으면 fail
const PREFIX = { CHANNEL: 'CHANNEL', FACILITY: 'FACILITY', REGION: 'REGION', INSTALLMENT: 'INSTALLMENT', BILLING: 'BILLING', CARD_FORM: 'CARD_FORM', CARD_MONTH: 'CARD_MONTH', OTHER_DISCOUNT: 'ISSUER_DISCOUNT' };
function mapCondition(c) {
  const vals = c.value.split('|').map(v => v.trim()).filter(Boolean);
  const from = `conditions.${c.cond_type}`;
  const include = c.effect === 'REQUIRE';
  switch (c.cond_type) {
    case 'AMOUNT':
      if ((include && c.operator === 'GTE') || (!include && c.operator === 'LT')) return { minPayment: Number(c.value) };
      return { fail: '결제액 상한 · 구간 조건은 들어갈 칸이 없음' };
    case 'WEEKDAY':
      if (include && c.operator === 'IN') return { json: { weekdays: vals } };
      return { fail: '요일 제외 조건은 conditions_json으로 못 나타냄' };
    case 'TIME':
      if (include && c.operator === 'BETWEEN' && vals.length === 2) return { json: { startTime: vals[0], endTime: vals[1] } };
      return { fail: '시간 조건 형식을 모름' };
    case 'HOLIDAY': return { fail: '공휴일 조건은 conditions_json에 키가 없음' };
    case 'OPTION': return { fail: '사용자가 고르는 조건은 표현 못 함' };
    case 'RANKED': return { fail: '자동 순위 조건은 표현 못 함' };
    case 'PAYMENT_METHOD':
      if (include) return { fail: '받으려면 특정 결제수단이어야 하는 조건은 서버가 확인할 칸이 없음' };
      return { targets: vals.map(v => ({ target_type: 'CONDITION', target_value: noteCode(`PAYMENT_METHOD:${upper(v)}`, from, v, '결제수단 제외'), is_excluded: true })) };
    case 'FACT': {
      if (vals.length !== 1 || c.operator !== 'EQ') return { fail: `서술 조건 형식을 모름(${c.value})` };
      const r = conditionValueCode(vals[0], from);
      return r.fail ? r : { targets: [{ target_type: 'CONDITION', target_value: r.code, is_excluded: !include }] };
    }
    default: {
      const p = PREFIX[c.cond_type];
      if (!p) return { fail: `조건 종류를 모름(${c.cond_type})` };
      if (!include) return { targets: vals.map(v => ({ target_type: 'CONDITION', target_value: noteCode(`${p}:${upper(v)}`, from, v, `${c.cond_type} 제외`), is_excluded: true })) };
      if (c.operator === 'NOT_IN') return { fail: `${c.cond_type} NOT_IN 조건은 표현 못 함` };
      if (vals.length === 1) return { targets: [{ target_type: 'CONDITION', target_value: noteCode(`${p}:${upper(vals[0])}`, from, vals[0], `${c.cond_type} 필수`), is_excluded: false }] };
      // 여러 값 중 하나(OR) — 포함 조건 행끼리는 모두 맞아야 하므로 하나의 RULE 코드로 묶는다(97 RULE:CHEOEUM_CAFE_ROUTE와 같은 방식)
      const code = `RULE:${c.cond_type}_${vals.map(upper).join('_OR_')}`;
      return { targets: [{ target_type: 'CONDITION', target_value: noteCode(code, from, c.value, `${c.cond_type} 중 하나`), is_excluded: false }] };
    }
  }
}

// 대상 행 → DB 대상(여러 개일 수 있음). 표현할 수 없으면 fail
const merchants = new Map(); // brand_code → { brand_code, name, category_code, merchant_key, basis }
function brandOf(key) {
  const ref = refMerchants.find(m => m.merchant_key === key);
  if (!ref) return { fail: `ref_merchants에 없는 브랜드(${key})` };
  const over = brandDict.get(key);
  const brand = over && over.brand_code ? over.brand_code : upper(key);
  let category, basis;
  if (seed97Brands.has(brand)) { category = seed97Brands.get(brand).category; basis = '97 시드 업종'; }
  else if (over && over.category_code) { category = over.category_code; basis = '임시 브랜드 사전'; }
  else {
    const d = categoryDict.get(ref.category);
    if (!d || d.kind !== 'CATEGORY' || d.yamu_codes.includes('|')) return { fail: `브랜드 업종을 정하지 못함(${key}, ${ref.category || '업종 빈칸'})` };
    category = d.yamu_codes; basis = `ref 업종 ${ref.category}`;
  }
  const prev = merchants.get(brand);
  if (prev && prev.category_code !== category) return { fail: `같은 브랜드 코드(${brand})에 업종이 둘` };
  if (!prev) merchants.set(brand, { brand_code: brand, name: seed97Brands.get(brand)?.name || ref.name, category_code: category, merchant_key: key, basis });
  return { brand };
}
function mapTarget(t) {
  const ex = t.mode === 'EXCLUDE';
  switch (t.target_type) {
    case 'ALL': return { targets: [{ target_type: 'ALL', is_excluded: ex }] };
    case 'CATEGORY': {
      const d = categoryDict.get(t.target_value);
      if (!d) return { fail: `업종 사전에 없음(${t.target_value})` };
      return { targets: d.yamu_codes.split('|').map(v => d.kind === 'CATEGORY'
        ? { target_type: 'CATEGORY', target_value: v, is_excluded: ex }
        : { target_type: 'CONDITION', target_value: noteCode(v, 'targets.CATEGORY', t.target_value, '업종이 아니라 거래 종류'), is_excluded: ex }) };
    }
    case 'MERCHANT': {
      const b = brandOf(t.target_value);
      return b.fail ? b : { targets: [{ target_type: 'BRAND', brand_code: b.brand, is_excluded: ex }] };
    }
    case 'CONDITION': {
      const r = conditionValueCode(t.target_value, 'targets.CONDITION');
      return r.fail ? r : { targets: [{ target_type: 'CONDITION', target_value: r.code, is_excluded: ex }] };
    }
    default: return { fail: `대상 종류를 모름(${t.target_type})` };
  }
}

// ── 혜택 1차: 혜택 행 · 조건 · 대상 ──
const REWARD = { BILLING_DISCOUNT: ['DISCOUNT', 'AT_BILLING'], ONSITE_DISCOUNT: ['DISCOUNT', 'AT_APPROVAL'], CASHBACK: ['CASHBACK', 'AFTER_BILLING'], POINTS: ['POINT', 'POINT_ACCRUAL'] };
const ROUNDING = { ROUND: ['HALF_UP', 1], FLOOR: ['DOWN', 1], FLOOR10: ['DOWN', 10], FLOOR100: ['DOWN', 100] };
const logicKeys = new Set(logic.map(l => `${l.card_id}/${l.benefit_key}`));
const addDay = (d) => { const t = new Date(`${d}T00:00:00Z`); t.setUTCDate(t.getUTCDate() + 1); return t.toISOString().slice(0, 10); };
const sourceUrl = (id) => (sources.find(s => s.source_id === id && /^https?:/.test(s.url)) || {}).url || '';

const candidates = [];
for (const b of benefits) {
  const key = b.benefit_key, card = b.card_id;
  if (!productOf.has(card)) continue;
  const why = (reason, detail) => exclude('benefits', card, key, reason, detail);
  if (b.status !== 'VERIFIED') { why('공식 확인 전(UNVERIFIED · CONFLICT)', b.status); continue; }
  if (b.yamu_support === 'NO') { why('제공자 판정 yamu_support = NO', b.note.slice(0, 120)); continue; }
  if (!REWARD[b.reward_type]) { why('결제 보상이 아님(서비스 · 수수료 면제 등)', b.reward_type); continue; }
  if (!['RATE', 'FIXED'].includes(b.calc_method)) { why('정률 · 정액이 아닌 계산(단위당 · 리터당 · 없음)', b.calc_method); continue; }
  if (b.calc_method === 'RATE' && b.rate_pct === '') { why('정률인데 비율 없음'); continue; }
  if (b.calc_method === 'FIXED' && b.fixed_amount === '') { why('정액인데 금액 없음'); continue; }
  if (b.reward_basis === 'MONTH_TOTAL') { why('한 달 합계로 한 번 주는 혜택은 칸이 없음'); continue; }
  if (b.tier_to !== '') { why('실적 구간 상한은 혜택 칸이 없음', b.tier_to); continue; }
  if (logicKeys.has(`${card}/${key}`)) { why('OR · 조건부 논리(A-condition_logic)는 표현 못 함'); continue; }

  const mine = (r) => r.card_id === card && (r.benefit_key === key || r.benefit_key === '*');
  let failed = null, minPayment = 0; const json = {}; const outTargets = [];
  for (const c of conditions.filter(mine)) {
    if (c.status === 'CONFLICT') { failed = ['조건이 자료 충돌', `${c.cond_type} ${c.value}`]; break; }
    const m = mapCondition(c);
    if (m.fail) { failed = [m.fail, `${c.benefit_key === '*' ? '카드 전체 ' : ''}${c.cond_type} ${c.effect} ${c.operator} ${c.value}`]; break; }
    if (c.status === 'UNVERIFIED') assume(card, key, '미확인 조건 유지', `${c.cond_type} ${c.value}`, '빼면 혜택이 넓어지므로 남겨 둠');
    if (m.minPayment != null) minPayment = Math.max(minPayment, m.minPayment);
    if (m.json) Object.assign(json, m.json);
    if (m.targets) outTargets.push(...m.targets);
  }
  if (!failed) for (const t of targets.filter(mine)) {
    if (t.status === 'UNVERIFIED' && t.mode === 'INCLUDE') { assume(card, key, '미확인 포함 대상 뺌', `${t.target_type} ${t.target_value}`, '공식 확인 전이라 혜택 범위를 넓히지 않음'); continue; }
    if (t.status === 'UNVERIFIED') assume(card, key, '미확인 제외 대상 유지', `${t.target_type} ${t.target_value}`, '빼면 혜택이 넓어지므로 남겨 둠');
    const m = mapTarget(t);
    if (m.fail) { failed = [m.fail, `${t.benefit_key === '*' ? '카드 전체 ' : ''}${t.mode} ${t.target_type} ${t.target_value}`]; break; }
    outTargets.push(...m.targets);
  }
  if (failed) { why(...failed); continue; }
  if (!outTargets.some(t => !t.is_excluded && ['ALL', 'BRAND', 'CATEGORY'].includes(t.target_type))) { why('적용 범위(전체 · 브랜드 · 업종)가 없음'); continue; }

  const [benefitType, timing] = REWARD[b.reward_type];
  const rounding = ROUNDING[b.rounding] || [null, null];
  if (!ROUNDING[b.rounding]) assume(card, key, 'rounding_mode · rounding_unit', 'NULL', `원본 ${b.rounding || '빈칸'}`);
  assume(card, key, 'rounding_stage', 'NULL', '거래별 · 합산 후 처리 단계 근거 없음');
  if (benefitType === 'POINT') assume(card, key, 'point_krw_rate', 'NULL', '포인트 1P 가치는 팀 결정 전(DB 적재 전 보완 P0)');
  const validUntil = b.valid_until ? addDay(b.valid_until) : '';
  if (b.valid_from && validUntil && validUntil <= b.valid_from) { why('적용 기간이 거꾸로', `${b.valid_from} ~ ${b.valid_until}`); continue; }
  const code = upper(key);
  if (code.length > 64) { why('혜택 코드가 64자를 넘음', code); continue; }
  candidates.push({
    card_id: card, benefit_key: key, stack_group: b.stack_group, yamu_support: b.yamu_support,
    row: {
      issuer_code: productOf.get(card).issuer_code, product_name: productOf.get(card).product_name, card_type: productOf.get(card).card_type,
      benefit_code: code, benefit_name: b.title.slice(0, 150), benefit_type: benefitType, calculation_type: b.calc_method,
      rate: b.calc_method === 'RATE' ? (Number(b.rate_pct) / 100).toFixed(6) : '', fixed_value: b.calc_method === 'FIXED' ? Number(b.fixed_amount).toFixed(6) : '',
      value_unit: benefitType === 'POINT' ? 'POINT' : 'KRW', point_krw_rate: '', application_timing: timing,
      rounding_mode: rounding[0] ?? '', rounding_unit: rounding[1] ?? '', rounding_stage: '',
      min_payment_amount: minPayment, min_performance_amount: b.tier_from === '' ? 0 : Number(b.tier_from),
      valid_from: b.valid_from, valid_until: validUntil,
      conditions_json: Object.keys(json).length ? JSON.stringify(json) : '', terms_url: sourceUrl(b.source_id) || productOf.get(card).terms_url,
    },
    targets: outTargets,
  });
}

// ── 혜택 2차: 한도 ──
const PERIOD = { TXN: 'PER_TRANSACTION', DAY: 'DAILY', MONTH: 'MONTHLY' };
function buildGroups(list) {
  const groups = new Map(); // card/limit_key → group
  for (const cand of list) for (const l of links.filter(x => x.card_id === cand.card_id && x.benefit_key === cand.benefit_key)) {
    const id = `${l.card_id}/${l.limit_key}`;
    if (!groups.has(id)) groups.set(id, { card_id: l.card_id, limit_key: l.limit_key, rows: limits.filter(r => r.card_id === l.card_id && r.limit_key === l.limit_key), benefits: [] });
    groups.get(id).benefits.push(cand);
  }
  for (const g of groups.values()) {
    const rows = [...g.rows].sort((a, z) => Number(a.tier_from) - Number(z.tier_from));
    if (!rows.length) { g.fail = 'limit_links에 있는 한도 행이 limits에 없음'; continue; }
    if (rows.every(r => r.measure === 'NONE')) { g.none = true; continue; }
    const bad = rows.find(r => !PERIOD[r.period] || !['BENEFIT_AMOUNT', 'COUNT'].includes(r.measure));
    if (bad) { g.fail = `지원하지 않는 한도(${bad.period}/${bad.measure})`; continue; }
    if (rows.some(r => r.status !== 'VERIFIED')) { g.fail = '한도 값이 공식 확인 전'; continue; }
    if (new Set(rows.map(r => `${r.period}/${r.measure}`)).size > 1) { g.fail = '한 묶음에 기간 · 단위가 섞임'; continue; }
    if (new Set(rows.map(r => r.tier_from)).size !== rows.length) { g.fail = '같은 실적 구간에 값이 둘'; continue; }
    if (rows.some(r => r.value === '' || !/^\d+$/.test(r.value))) { g.fail = '한도 값이 정수가 아님'; continue; }
    const units = new Set(g.benefits.map(c => c.row.value_unit));
    if (rows[0].measure === 'BENEFIT_AMOUNT' && units.size > 1) { g.fail = '원과 포인트 혜택이 한 금액 한도를 같이 씀'; continue; }
    const code = upper(g.limit_key);
    if (code.length > 64) { g.fail = '한도 코드가 64자를 넘음'; continue; }
    const periodKo = { PER_TRANSACTION: '건별', DAILY: '일', MONTHLY: '월' }[PERIOD[rows[0].period]];
    const unit = rows[0].measure === 'COUNT' ? 'COUNT' : [...units][0];
    const name = g.benefits.length === 1 && rows[0].scope === 'BENEFIT'
      ? `${g.benefits[0].row.benefit_name} ${periodKo} ${unit === 'COUNT' ? '횟수' : '혜택'} 한도`
      : `공유 ${periodKo} ${unit === 'COUNT' ? '횟수' : '혜택'} 한도(${g.limit_key})`;
    const flat = rows.length === 1 && Number(rows[0].tier_from) === 0;
    g.ok = {
      group: { issuer_code: g.benefits[0].row.issuer_code, product_name: g.benefits[0].row.product_name, card_type: g.benefits[0].row.card_type,
        group_code: code, group_name: name.slice(0, 150), period_type: PERIOD[rows[0].period], limit_unit: unit, base_limit_value: flat ? Number(rows[0].value) : '' },
      tiers: flat ? [] : rows.map((r, i) => ({ min_performance_amount: Number(r.tier_from), max_performance_amount: rows[i + 1] ? Number(rows[i + 1].tier_from) : '', limit_value: Number(r.value) })),
    };
  }
  return groups;
}
let included = candidates;
let groups = buildGroups(included);
for (let pass = 0; pass < 3; pass++) {
  const failedCards = new Set();
  for (const g of groups.values()) if (g.fail) for (const c of g.benefits) failedCards.add(`${c.card_id}/${c.benefit_key}\u0000${g.fail}\u0000${g.limit_key}`);
  if (!failedCards.size) break;
  const drop = new Set();
  for (const f of failedCards) { const [ck, reason, lk] = f.split('\u0000'); const [card, key] = ck.split('/'); exclude('benefits', card, key, reason, `한도 ${lk}`); drop.add(ck); }
  included = included.filter(c => !drop.has(`${c.card_id}/${c.benefit_key}`));
  groups = buildGroups(included);
}
// 혜택 중첩 방식(stack_group)은 칸이 없다 — 한 카드에 묶음이 여럿이면 가정으로 남긴다
for (const p of products) {
  const sg = [...new Set(included.filter(c => c.card_id === p.card_id).map(c => c.stack_group).filter(Boolean))];
  if (sg.length > 1) assume(p.card_id, '*', 'stack_group', sg.join('|'), '같은 묶음 하나만 · 다른 묶음 합산 규칙은 BE 미정이라 DB에 남기지 않음');
}

// ── DB 모양으로 펼치기 ──
const usedCards = new Set(included.map(c => c.card_id));
for (const p of products) if (!usedCards.has(p.card_id)) exclude('cards', p.card_id, '', '넣을 수 있는 혜택이 하나도 없음', '카드 행은 넣지 않음');
const outProducts = products.filter(p => usedCards.has(p.card_id));
const outBenefits = included.map(c => c.row);
const targetRows = new Map();
for (const c of included) for (const t of c.targets) {
  const row = { issuer_code: c.row.issuer_code, product_name: c.row.product_name, card_type: c.row.card_type, benefit_code: c.row.benefit_code,
    target_type: t.target_type, target_value: t.target_value || '', brand_code: t.brand_code || '', is_excluded: t.is_excluded ? 1 : 0 };
  targetRows.set([row.product_name, row.benefit_code, row.target_type, row.is_excluded, row.target_value, row.brand_code].join('\u0000'), row);
}
const outTargets = [...targetRows.values()];
const okGroups = [...groups.values()].filter(g => g.ok);
const outGroups = okGroups.map(g => g.ok.group);
const outTiers = okGroups.flatMap(g => g.ok.tiers.map(t => ({ issuer_code: g.ok.group.issuer_code, product_name: g.ok.group.product_name, card_type: g.ok.group.card_type, group_code: g.ok.group.group_code, ...t })));
const outLinks = okGroups.flatMap(g => g.benefits.map(c => ({ issuer_code: c.row.issuer_code, product_name: c.row.product_name, card_type: c.row.card_type, benefit_code: c.row.benefit_code, group_code: g.ok.group.group_code })));
const usedBrands = new Set(outTargets.map(t => t.brand_code).filter(Boolean));
const outMerchants = [...merchants.values()].filter(m => usedBrands.has(m.brand_code)).sort((a, z) => a.brand_code.localeCompare(z.brand_code));

// ── 쓰기 ──
const PK = ['issuer_code', 'product_name', 'card_type'];
const out = (p) => join(here, p);
writeCsv(out('db/card_product.csv'), [...PK, 'terms_url', 'card_id'], outProducts);
writeCsv(out('db/card_benefit.csv'), [...PK, 'benefit_code', 'benefit_name', 'benefit_type', 'calculation_type', 'rate', 'fixed_value', 'value_unit', 'point_krw_rate', 'application_timing', 'rounding_mode', 'rounding_unit', 'rounding_stage', 'min_payment_amount', 'min_performance_amount', 'valid_from', 'valid_until', 'conditions_json', 'terms_url'], outBenefits);
writeCsv(out('db/benefit_target.csv'), [...PK, 'benefit_code', 'target_type', 'target_value', 'brand_code', 'is_excluded'], outTargets);
writeCsv(out('db/limit_group.csv'), [...PK, 'group_code', 'group_name', 'period_type', 'limit_unit', 'base_limit_value'], outGroups);
writeCsv(out('db/tier_limit.csv'), [...PK, 'group_code', 'min_performance_amount', 'max_performance_amount', 'limit_value'], outTiers);
writeCsv(out('db/benefit_limit_link.csv'), [...PK, 'benefit_code', 'group_code'], outLinks);
writeCsv(out('db/merchant.csv'), ['brand_code', 'name', 'category_code', 'merchant_key', 'basis'], outMerchants);
writeCsv(out('codes/conditions.csv'), ['yamu_code', 'from', 'raw', 'basis'], [...conditionCodes.values()].filter(c => outTargets.some(t => t.target_value === c.yamu_code)).sort((a, z) => a.yamu_code.localeCompare(z.yamu_code)));
writeCsv(out('report/excluded.csv'), ['table', 'card_id', 'key', 'reason', 'detail'], excluded);
writeCsv(out('report/assumptions.csv'), ['card_id', 'key', 'item', 'value', 'reason'], assumptions);

// 미확인 · 충돌 목록 — 20장 전부, 카드별
const loaded = new Set(included.map(c => `${c.card_id}/${c.benefit_key}`));
const cardName = new Map(cards.map(c => [c.card_id, c.product_name]));
let md = `# 미확인 · 충돌 목록 (카드 20종)\n\n> 생성 파일. 입력: v2 \`reports/미확인-충돌-통합.csv\` ${unverified.length}행. '적재'는 이번 시드에 그 혜택이 들어갔는지다(\`*\`는 카드 전체 행).\n> 같은 쟁점이 여러 표에 나오면 여러 줄이라, 줄 수를 문제 개수로 세지 않는다.\n\n`;
md += `| 카드 | 파트 | 줄 | UNVERIFIED | CONFLICT |\n|---|---|---|---|---|\n`;
const byCard = new Map();
for (const u of unverified) { if (!byCard.has(u.card_id)) byCard.set(u.card_id, []); byCard.get(u.card_id).push(u); }
for (const [card, list] of [...byCard].sort()) md += `| ${cardName.get(card) || card} (\`${card}\`) | ${list[0].part} | ${list.length} | ${list.filter(u => u.status === 'UNVERIFIED').length} | ${list.filter(u => u.status === 'CONFLICT').length} |\n`;
for (const [card, list] of [...byCard].sort()) {
  md += `\n## ${cardName.get(card) || card} (\`${card}\`)\n\n| 표 | 줄 | 혜택 | 상태 | 내용 | 처리 | 적재 |\n|---|---|---|---|---|---|---|\n`;
  for (const u of list) {
    const state = SEED97_CARDS.has(card) ? '97 시드' : u.benefit_key && u.benefit_key !== '*' ? (loaded.has(`${card}/${u.benefit_key}`) ? '들어감' : '안 들어감') : '-';
    const cell = (s) => String(s || '').replace(/\|/g, '/').replace(/\s+/g, ' ').slice(0, 140);
    md += `| ${u.table} | ${u.csv_row} | ${cell(u.benefit_key)} | ${u.status} | ${cell(u.description)} | ${cell(u.resolution)} | ${state} |\n`;
  }
}
writeFileSync(out('report/unverified-conflicts.md'), md);

// ── SQL ──
const q = (v) => (v === '' || v == null ? 'NULL' : typeof v === 'number' ? String(v) : `'${String(v).replace(/\\/g, '\\\\').replace(/'/g, "''")}'`);
const values = (rows, cols) => rows.map(r => `(${cols.map(c => q(r[c])).join(', ')})`).join(',\n') + ';';
const joinProduct = 'JOIN card_product p ON p.issuer_code = s.issuer_code AND p.product_name = s.product_name AND p.card_type = s.card_type';
const inputHash = createHash('sha256').update(['cards', 'benefits', 'targets', 'conditions', 'limits', 'limit_links', 'ref_merchants', 'sources'].map(n => readFileSync(join(src, 'data', `${n}.csv`))).reduce((a, b) => Buffer.concat([a, b]), Buffer.alloc(0))).digest('hex');
const sql = `-- S15P31E103-178 / A·B 카드 20종 v2 중 97 시드 밖 ${outProducts.length}장 / MySQL 8.4.11
-- 생성 파일이다. 직접 고치지 말고 be/seed/catalog-ab20/build-seed.mjs로 다시 만든다(README).
-- 입력 data/*.csv 8개 sha256 ${inputHash}
-- 업종 · 조건 코드는 POL-004 확정 전 임시 코드다(codes/). 01-card-catalog.sql과 순서 상관없이 돌고, 다시 돌려도 행이 늘지 않는다.
-- 한 번에 실행하고 첫 오류에서 멈춘다(mysql --force 금지). 접속 정보는 이 파일에 넣지 않는다.
SET NAMES utf8mb4;
SET time_zone = '+00:00';
-- 같은 세션에서 앞선 실행이 중간에 멈췄다면 임시 표가 남아 있을 수 있다.
DROP TEMPORARY TABLE IF EXISTS ab20_product, ab20_benefit, ab20_merchant, ab20_guard, ab20_target, ab20_limit, ab20_tier, ab20_link;

START TRANSACTION;
SET @ab20_now = UTC_TIMESTAMP(6);

CREATE TEMPORARY TABLE ab20_product (issuer_code VARCHAR(32) NOT NULL, product_name VARCHAR(150) NOT NULL, card_type VARCHAR(16) NOT NULL, terms_url VARCHAR(1000) NULL);
INSERT INTO ab20_product VALUES
${values(outProducts, [...PK, 'terms_url'])}

INSERT INTO card_product (issuer_code, product_name, card_type, terms_url, created_at, updated_at)
SELECT s.issuer_code, s.product_name, s.card_type, s.terms_url, @ab20_now, @ab20_now
FROM ab20_product s
WHERE NOT EXISTS (SELECT 1 FROM card_product p WHERE p.issuer_code = s.issuer_code AND p.product_name = s.product_name AND p.card_type = s.card_type);

CREATE TEMPORARY TABLE ab20_benefit (
    issuer_code VARCHAR(32) NOT NULL, product_name VARCHAR(150) NOT NULL, card_type VARCHAR(16) NOT NULL,
    benefit_code VARCHAR(64) NOT NULL, benefit_name VARCHAR(150) NOT NULL, benefit_type VARCHAR(16) NOT NULL, calculation_type VARCHAR(16) NOT NULL,
    rate DECIMAL(9,6) NULL, fixed_value DECIMAL(19,6) NULL, value_unit VARCHAR(16) NOT NULL, application_timing VARCHAR(32) NULL,
    rounding_mode VARCHAR(16) NULL, rounding_unit SMALLINT NULL, min_payment BIGINT NOT NULL, min_performance BIGINT NOT NULL,
    valid_from DATE NULL, valid_until DATE NULL, conditions JSON NULL, terms_url VARCHAR(1000) NULL
);
INSERT INTO ab20_benefit VALUES
${values(outBenefits, [...PK, 'benefit_code', 'benefit_name', 'benefit_type', 'calculation_type', 'rate', 'fixed_value', 'value_unit', 'application_timing', 'rounding_mode', 'rounding_unit', 'min_payment_amount', 'min_performance_amount', 'valid_from', 'valid_until', 'conditions_json', 'terms_url'])}

-- 포인트 가치 · 반올림 단계처럼 정해지지 않은 값은 NULL로 둔다(report/assumptions.csv). 이미 있는 정책 버전은 덮어쓰지 않는다.
INSERT INTO card_benefit (card_product_id, benefit_code, policy_version, benefit_name, benefit_type, calculation_type, rate, fixed_value,
    value_unit, point_krw_rate, application_timing, rounding_mode, rounding_unit, min_payment_amount, min_performance_amount,
    valid_from, valid_until, conditions_json, terms_url, created_at)
SELECT p.card_product_id, s.benefit_code, 1, s.benefit_name, s.benefit_type, s.calculation_type, s.rate, s.fixed_value,
    s.value_unit, NULL, s.application_timing, s.rounding_mode, s.rounding_unit, s.min_payment, s.min_performance,
    s.valid_from, s.valid_until, s.conditions, s.terms_url, @ab20_now
FROM ab20_benefit s
${joinProduct}
WHERE NOT EXISTS (SELECT 1 FROM card_benefit b WHERE b.card_product_id = p.card_product_id AND b.benefit_code = s.benefit_code AND b.policy_version = 1);

CREATE TEMPORARY TABLE ab20_merchant (brand_code VARCHAR(64) NOT NULL PRIMARY KEY, name VARCHAR(150) NOT NULL, category_code VARCHAR(64) NOT NULL);
INSERT INTO ab20_merchant VALUES
${values(outMerchants, ['brand_code', 'name', 'category_code'])}

-- 같은 브랜드가 다른 업종으로 이미 있으면 바꾸지 않고 멈춘다(97과 같은 방식).
CREATE TEMPORARY TABLE ab20_guard (ok INT NOT NULL CHECK (ok = 1));
INSERT INTO ab20_guard
SELECT 0 FROM ab20_merchant s JOIN merchant m ON m.brand_code = s.brand_code
WHERE m.merchant_category_code <> s.category_code;

INSERT INTO merchant (merchant_name, brand_code, merchant_category_code, created_at, updated_at)
SELECT s.name, s.brand_code, s.category_code, @ab20_now, @ab20_now
FROM ab20_merchant s
WHERE NOT EXISTS (SELECT 1 FROM merchant m WHERE m.brand_code = s.brand_code);

CREATE TEMPORARY TABLE ab20_target (
    issuer_code VARCHAR(32) NOT NULL, product_name VARCHAR(150) NOT NULL, card_type VARCHAR(16) NOT NULL, benefit_code VARCHAR(64) NOT NULL,
    target_type VARCHAR(16) NOT NULL, target_value VARCHAR(128) NULL, brand_code VARCHAR(64) NULL, is_excluded BOOLEAN NOT NULL
);
INSERT INTO ab20_target VALUES
${values(outTargets, [...PK, 'benefit_code', 'target_type', 'target_value', 'brand_code', 'is_excluded'])}

INSERT INTO benefit_target (card_benefit_id, target_type, merchant_id, target_value, is_excluded, created_at)
SELECT b.card_benefit_id, s.target_type, m.merchant_id, s.target_value, s.is_excluded, @ab20_now
FROM ab20_target s
${joinProduct}
JOIN card_benefit b ON b.card_product_id = p.card_product_id AND b.benefit_code = s.benefit_code AND b.policy_version = 1
LEFT JOIN merchant m ON m.brand_code = s.brand_code
WHERE NOT EXISTS (SELECT 1 FROM benefit_target t
    WHERE t.card_benefit_id = b.card_benefit_id AND t.target_type = s.target_type AND t.is_excluded = s.is_excluded
      AND t.target_key = (CASE WHEN s.target_type = 'BRAND' THEN CAST(m.merchant_id AS CHAR) WHEN s.target_type = 'ALL' THEN '*' ELSE s.target_value END));

CREATE TEMPORARY TABLE ab20_limit (
    issuer_code VARCHAR(32) NOT NULL, product_name VARCHAR(150) NOT NULL, card_type VARCHAR(16) NOT NULL,
    group_code VARCHAR(64) NOT NULL, group_name VARCHAR(150) NOT NULL, period_type VARCHAR(16) NOT NULL, limit_unit VARCHAR(16) NOT NULL, base_limit_value BIGINT NULL
);
INSERT INTO ab20_limit VALUES
${values(outGroups, [...PK, 'group_code', 'group_name', 'period_type', 'limit_unit', 'base_limit_value'])}

INSERT INTO limit_group (card_product_id, group_code, policy_version, group_name, period_type, limit_unit, base_limit_value, created_at)
SELECT p.card_product_id, s.group_code, 1, s.group_name, s.period_type, s.limit_unit, s.base_limit_value, @ab20_now
FROM ab20_limit s
${joinProduct}
WHERE NOT EXISTS (SELECT 1 FROM limit_group g WHERE g.card_product_id = p.card_product_id AND g.group_code = s.group_code AND g.policy_version = 1);
${outTiers.length ? `
CREATE TEMPORARY TABLE ab20_tier (
    issuer_code VARCHAR(32) NOT NULL, product_name VARCHAR(150) NOT NULL, card_type VARCHAR(16) NOT NULL,
    group_code VARCHAR(64) NOT NULL, lower_amount BIGINT NOT NULL, upper_amount BIGINT NULL, limit_value BIGINT NOT NULL
);
INSERT INTO ab20_tier VALUES
${values(outTiers, [...PK, 'group_code', 'min_performance_amount', 'max_performance_amount', 'limit_value'])}

INSERT INTO tier_limit (limit_group_id, min_performance_amount, max_performance_amount, limit_value, created_at)
SELECT g.limit_group_id, s.lower_amount, s.upper_amount, s.limit_value, @ab20_now
FROM ab20_tier s
${joinProduct}
JOIN limit_group g ON g.card_product_id = p.card_product_id AND g.group_code = s.group_code AND g.policy_version = 1
WHERE NOT EXISTS (SELECT 1 FROM tier_limit t WHERE t.limit_group_id = g.limit_group_id AND t.min_performance_amount = s.lower_amount);
` : ''}
CREATE TEMPORARY TABLE ab20_link (
    issuer_code VARCHAR(32) NOT NULL, product_name VARCHAR(150) NOT NULL, card_type VARCHAR(16) NOT NULL, benefit_code VARCHAR(64) NOT NULL, group_code VARCHAR(64) NOT NULL
);
INSERT INTO ab20_link VALUES
${values(outLinks, [...PK, 'benefit_code', 'group_code'])}

INSERT INTO benefit_limit_link (card_product_id, card_benefit_id, limit_group_id, created_at)
SELECT p.card_product_id, b.card_benefit_id, g.limit_group_id, @ab20_now
FROM ab20_link s
${joinProduct}
JOIN card_benefit b ON b.card_product_id = p.card_product_id AND b.benefit_code = s.benefit_code AND b.policy_version = 1
JOIN limit_group g ON g.card_product_id = p.card_product_id AND g.group_code = s.group_code AND g.policy_version = 1
WHERE NOT EXISTS (SELECT 1 FROM benefit_limit_link l WHERE l.card_benefit_id = b.card_benefit_id AND l.limit_group_id = g.limit_group_id);

COMMIT;

DROP TEMPORARY TABLE ab20_product, ab20_benefit, ab20_merchant, ab20_guard, ab20_target, ab20_limit${outTiers.length ? ', ab20_tier' : ''}, ab20_link;
`;
writeFileSync(join(here, '..', '02-card-catalog-ab20.sql'), sql);

const summary = { cards: outProducts.length, benefits: outBenefits.length, targets: outTargets.length, limit_groups: outGroups.length, tier_limits: outTiers.length, links: outLinks.length, merchants: outMerchants.length, condition_codes: [...conditionCodes.values()].filter(c => outTargets.some(t => t.target_value === c.yamu_code)).length, excluded: excluded.length, assumptions: assumptions.length };
writeFileSync(out('report/summary.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(summary);
