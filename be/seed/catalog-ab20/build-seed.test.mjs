import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, cpSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

test('Point Plan 전체를 보류하고 다른 카드와 재생성 결과는 유지한다', () => {
  const temp = mkdtempSync(join(tmpdir(), 'yamu-seed-review-'));
  try {
    const original = dirname(fileURLToPath(import.meta.url));
    const generator = join(temp, 'seed', 'catalog-ab20');
    const source = join(temp, 'input');
    mkdirSync(generator, { recursive: true });
    cpSync(join(original, 'build-seed.mjs'), join(generator, 'build-seed.mjs'));
    cpSync(join(original, 'target-groups.mjs'), join(generator, 'target-groups.mjs'));
    cpSync(join(original, 'codes'), join(generator, 'codes'), { recursive: true });
    cpSync(join(original, '..', '01-card-catalog.sql'), join(generator, '..', '01-card-catalog.sql'));
    const csv = (path, columns, rows = []) => {
      mkdirSync(dirname(path), { recursive: true });
      const cell = value => '"' + String(value ?? '').replaceAll('"', '""') + '"';
      writeFileSync(path, [columns, ...rows.map(r => columns.map(c => r[c] ?? ''))].map(r => r.map(cell).join(',')).join('\n') + '\n');
    };
    const data = (name, columns, rows) => csv(join(source, 'data', name + '.csv'), columns, rows);
    data('cards', ['card_id', 'issuer', 'product_name', 'card_type'], [
      {card_id:'shinhan-pointplan', issuer:'신한카드', product_name:'신한카드 Point Plan', card_type:'CREDIT'},
      {card_id:'test-lotte', issuer:'롯데카드', product_name:'유지할 테스트 카드', card_type:'CREDIT'},
    ]);
    const keys = ['card_id','benefit_key','status','yamu_support','reward_type','calc_method','rate_pct','fixed_amount','reward_basis','tier_to','tier_from','title','rounding','valid_from','valid_until','source_id','note','stack_group'];
    data('benefits', keys, [ ['shinhan-pointplan','daily-1m'], ['shinhan-pointplan','weekend-dining'], ['test-lotte','base'] ].map(([card_id,benefit_key]) => ({card_id,benefit_key,status:'VERIFIED',yamu_support:'YES',reward_type:'BILLING_DISCOUNT',calc_method:'RATE',rate_pct:'1',title:'테스트 할인'})));
    data('targets', ['card_id','benefit_key','mode','target_type','target_value','status'], [{card_id:'test-lotte',benefit_key:'base',mode:'INCLUDE',target_type:'ALL',status:'VERIFIED'}]);
    data('limits', ['card_id','limit_key','period','measure','tier_from','value','status','scope'], [{card_id:'test-lotte',limit_key:'month',period:'MONTH',measure:'BENEFIT_AMOUNT',tier_from:'0',value:'10000',status:'VERIFIED',scope:'BENEFIT'}]);
    data('limit_links', ['card_id','benefit_key','limit_key'], [{card_id:'test-lotte',benefit_key:'base',limit_key:'month'}]);
    data('sources', ['card_id','source_id','kind','url']);
    data('conditions', ['card_id','benefit_key','cond_type','value','effect','operator','status']);
    data('ref_merchants', ['merchant_key','category','name']);
    for (const file of ['A-predicate_dictionary.csv','A-condition_logic.csv','미확인-충돌-통합.csv']) csv(join(source,'reports',file), ['card_id','benefit_key','code','kind']);
    const generate = () => {
      const result = spawnSync(process.execPath, [join(generator,'build-seed.mjs'),source], {encoding:'utf8'});
      assert.equal(result.status, 0, result.stderr);
    };
    generate();
    for (const file of ['card_product','card_benefit','benefit_target','limit_group','tier_limit','benefit_limit_link','merchant']) {
      assert.doesNotMatch(readFileSync(join(generator,'db',file+'.csv'),'utf8'), /Point Plan|shinhan-pointplan/);
    }
    const excluded = readFileSync(join(generator,'report','excluded.csv'),'utf8');
    assert.match(excluded, /shinhan-pointplan,daily-1m,Point Plan 카드 전체 적재 보류/);
    assert.match(excluded, /shinhan-pointplan,weekend-dining,Point Plan 카드 전체 적재 보류/);
    assert.match(readFileSync(join(generator,'db','card_benefit.csv'),'utf8'), /유지할 테스트 카드.*BASE/);
    const sql = readFileSync(join(generator,'..','02-card-catalog-ab20.sql'),'utf8');
    assert.doesNotMatch(sql, /Point Plan|INTEGRATED_FAMILY_MONTH/);
    generate();
    assert.equal(readFileSync(join(generator,'..','02-card-catalog-ab20.sql'),'utf8'), sql);
  } finally {
    rmSync(temp, {recursive:true,force:true});
  }
});
