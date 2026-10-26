-- 人工财务调整单独追加，原始供应商用量与结算金额保持不变。
ALTER TABLE quota_ledger DROP CONSTRAINT quota_ledger_kind_check;
ALTER TABLE quota_ledger ADD CONSTRAINT quota_ledger_kind_check CHECK(kind IN ('GRANT','RESERVE','SETTLE','RELEASE','RECONCILING','ADJUSTMENT'));
CREATE TABLE billing_adjustment (
 id uuid PRIMARY KEY,
 run_id uuid NOT NULL REFERENCES generation_run(id),
 account_id uuid NOT NULL REFERENCES quota_account(id),
 direction varchar(6) NOT NULL CHECK(direction IN ('DEBIT','CREDIT')),
 amount numeric(20,6) NOT NULL CHECK(amount>0),
 reason varchar(1000) NOT NULL CHECK(length(trim(reason))>0),
 evidence_ref varchar(1000) NOT NULL CHECK(length(trim(evidence_ref))>0),
 created_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX billing_adjustment_run_idx ON billing_adjustment(run_id,created_at,id);
CREATE INDEX billing_adjustment_account_idx ON billing_adjustment(account_id,created_at,id);
CREATE TRIGGER billing_adjustment_immutable BEFORE UPDATE OR DELETE ON billing_adjustment FOR EACH ROW EXECUTE FUNCTION protect_quota_ledger();
COMMENT ON TABLE billing_adjustment IS '仅对已结算运行追加金额调整，独立审计；不得用人工原因替代供应商未知用量核对';

-- 调整只改变已花费金额，禁止修改词元数、授予和冻结余额。
ALTER TABLE quota_ledger DROP CONSTRAINT quota_ledger_delta_shape;
ALTER TABLE quota_ledger ADD CONSTRAINT quota_ledger_delta_shape CHECK (
 (kind='GRANT' AND grant_tokens>=0 AND grant_amount>=0 AND (grant_tokens>0 OR grant_amount>0)
  AND reserve_tokens=0 AND spend_tokens=0 AND reserve_amount=0 AND spend_amount=0)
 OR (kind='RESERVE' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens>0 AND reserve_amount>=0 AND spend_tokens=0 AND spend_amount=0)
 OR (kind='SETTLE' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens<0 AND reserve_amount<=0 AND spend_tokens>=0 AND spend_amount>=0)
 OR (kind='RELEASE' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens<0 AND reserve_amount<=0 AND spend_tokens=0 AND spend_amount=0)
 OR (kind='RECONCILING' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens=0 AND reserve_amount=0 AND spend_tokens=0 AND spend_amount=0)
 OR (kind='ADJUSTMENT' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens=0 AND reserve_amount=0 AND spend_tokens=0 AND spend_amount<>0)
);
