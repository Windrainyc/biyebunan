-- 已完成的人工裁决收到不同原调用证据后，允许双人复核追加更正；原预占和证据不覆盖。
ALTER TABLE usage_adjudication DROP CONSTRAINT usage_adjudication_kind_check;
ALTER TABLE usage_adjudication ADD CONSTRAINT usage_adjudication_kind_check CHECK(kind IN ('USAGE','NO_CHARGE','CORRECTION'));
ALTER TABLE usage_adjudication DROP CONSTRAINT usage_adjudication_check;
ALTER TABLE usage_adjudication ADD CONSTRAINT adjudication_usage_shape CHECK(
 (kind IN ('USAGE','CORRECTION') AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL AND cached_tokens IS NOT NULL AND input_tokens BETWEEN 0 AND 1000000000 AND output_tokens BETWEEN 0 AND 1000000000 AND cached_tokens BETWEEN 0 AND input_tokens)
 OR(kind IN ('NO_CHARGE','CORRECTION') AND input_tokens IS NULL AND output_tokens IS NULL AND cached_tokens IS NULL));
-- 原调用证据不可变，同运行最多一次批准更正；拒绝的更正允许重新提交。
CREATE UNIQUE INDEX adjudication_one_correction ON usage_adjudication(run_id) WHERE kind='CORRECTION' AND state='APPROVED';
ALTER TABLE quota_ledger DROP CONSTRAINT quota_ledger_kind_check;
ALTER TABLE quota_ledger ADD CONSTRAINT quota_ledger_kind_check CHECK(kind IN ('GRANT','RESERVE','SETTLE','RELEASE','RECONCILING','ADJUSTMENT','CORRECTION'));
ALTER TABLE quota_ledger DROP CONSTRAINT quota_ledger_delta_shape;
ALTER TABLE quota_ledger ADD CONSTRAINT quota_ledger_delta_shape CHECK (
 (kind='GRANT' AND grant_tokens>=0 AND grant_amount>=0 AND (grant_tokens>0 OR grant_amount>0) AND reserve_tokens=0 AND spend_tokens=0 AND reserve_amount=0 AND spend_amount=0)
 OR (kind='RESERVE' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens>0 AND reserve_amount>=0 AND spend_tokens=0 AND spend_amount=0)
 OR (kind='SETTLE' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens<0 AND reserve_amount<=0 AND spend_tokens>=0 AND spend_amount>=0)
 OR (kind='RELEASE' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens<0 AND reserve_amount<=0 AND spend_tokens=0 AND spend_amount=0)
 OR (kind='RECONCILING' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens=0 AND reserve_amount=0 AND spend_tokens=0 AND spend_amount=0)
 OR (kind='ADJUSTMENT' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens=0 AND reserve_amount=0 AND spend_tokens=0 AND spend_amount<>0)
 OR (kind='CORRECTION' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens=0 AND reserve_amount=0));
COMMENT ON INDEX adjudication_one_correction IS '防止并发复核重复应用原调用更正；还由账户锁及账本唯一键防重';
-- 只读投影统一账单与导出；原值和有效值分开，未知用量仍为NULL。
CREATE VIEW usage_billing_projection AS
SELECT h.*,
 CASE WHEN c.id IS NOT NULL THEN coalesce(c.input_tokens,0) ELSE h.input_tokens END AS effective_input_tokens,
 CASE WHEN c.id IS NOT NULL THEN coalesce(c.output_tokens,0) ELSE h.output_tokens END AS effective_output_tokens,
 CASE WHEN c.id IS NOT NULL THEN coalesce(c.cached_tokens,0) ELSE h.cached_tokens END AS effective_cached_tokens,
 coalesce(l.spend_amount,0) AS correction_amount,
 coalesce(b.amount,0) AS adjustment_amount,
 CASE WHEN h.state IN ('SETTLED','RELEASED') THEN coalesce(h.actual_amount,0)+coalesce(l.spend_amount,0)+coalesce(b.amount,0) END AS net_amount,
 CASE WHEN c.id IS NOT NULL THEN 'CORRECTED_ORIGINAL_CALL'
 WHEN EXISTS(SELECT 1 FROM usage_adjudication u WHERE u.run_id=h.run_id AND u.state='APPROVED') THEN 'MANUAL_ADJUDICATION'
 WHEN EXISTS(SELECT 1 FROM run_usage_evidence e WHERE e.run_id=h.run_id) THEN 'ORIGINAL_CALL' ELSE 'UNCONFIRMED' END AS evidence_source
FROM quota_hold h
LEFT JOIN usage_adjudication c ON c.run_id=h.run_id AND c.kind='CORRECTION' AND c.state='APPROVED'
LEFT JOIN quota_ledger l ON l.source_id=h.run_id AND l.account_id=h.account_id AND l.kind='CORRECTION'
LEFT JOIN LATERAL (SELECT sum(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END) AS amount FROM billing_adjustment WHERE run_id=h.run_id) b ON true;
