// Emit the same stable singleton paths for both fresh seeding and migration.
export function withTargetGroups(sql) {
  const start = sql.indexOf('INSERT INTO benefit_target (');
  if (start < 0) throw new Error('target INSERT missing');
  const end = sql.indexOf(';', start) + 1;
  const original = sql.slice(start, end);
  if (sql.includes('-- target groups generated')) return sql;
  const now = original.includes('@seed97_now') ? '@seed97_now' : '@ab20_now';
  const from = original.slice(original.indexOf('FROM '), original.indexOf('WHERE NOT EXISTS'));
  const normalized = "IF(s.target_type='CONDITION',s.is_excluded,0)";
  const insert = original.replace('SELECT b.card_benefit_id', 'SELECT DISTINCT b.card_benefit_id')
    .replace('s.target_value, s.is_excluded,', `s.target_value, ${normalized},`)
    .replace('t.is_excluded = s.is_excluded', `t.is_excluded = ${normalized}`);
  const key = "CONCAT('LEGACY_',s.is_excluded,'_',LEFT(SHA2(CONCAT(s.target_type,':',COALESCE(s.brand_code,s.target_value,'*')),256),40))";
  const groups = `
-- target groups generated; CONDITION stays common, not a group member.
INSERT INTO benefit_target_group(card_benefit_id,group_code,is_excluded,created_at)
SELECT DISTINCT b.card_benefit_id, ${key}, s.is_excluded, ${now}
${from}
WHERE s.target_type <> 'CONDITION' AND NOT EXISTS (
 SELECT 1 FROM benefit_target_group g WHERE g.card_benefit_id=b.card_benefit_id AND g.group_code=${key});
INSERT INTO benefit_target_group_member(benefit_target_group_id,benefit_target_id,card_benefit_id)
SELECT DISTINCT g.benefit_target_group_id,t.benefit_target_id,b.card_benefit_id
${from}
JOIN benefit_target_group g ON g.card_benefit_id=b.card_benefit_id AND g.group_code=${key}
JOIN benefit_target t ON t.card_benefit_id=b.card_benefit_id AND t.target_type=s.target_type
 AND t.merchant_id <=> m.merchant_id AND t.target_value <=> s.target_value AND t.is_excluded=0
WHERE s.target_type <> 'CONDITION' AND NOT EXISTS (
 SELECT 1 FROM benefit_target_group_member x WHERE x.benefit_target_group_id=g.benefit_target_group_id AND x.benefit_target_id=t.benefit_target_id);
`;
  return sql.slice(0, start) + insert + groups + sql.slice(end);
}
