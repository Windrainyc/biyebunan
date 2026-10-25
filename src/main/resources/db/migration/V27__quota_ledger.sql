-- 月额度以UTC自然月为周期，余额只由同事务账本与数据库账户更新维护。
CREATE TABLE quota_account (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES user_account(id),period_start timestamptz NOT NULL,period_end timestamptz NOT NULL,
 granted_tokens bigint NOT NULL DEFAULT 0 CHECK(granted_tokens>=0),reserved_tokens bigint NOT NULL DEFAULT 0 CHECK(reserved_tokens>=0),spent_tokens bigint NOT NULL DEFAULT 0 CHECK(spent_tokens>=0),
 granted_amount numeric(20,6) NOT NULL DEFAULT 0 CHECK(granted_amount>=0),reserved_amount numeric(20,6) NOT NULL DEFAULT 0 CHECK(reserved_amount>=0),spent_amount numeric(20,6) NOT NULL DEFAULT 0 CHECK(spent_amount>=0),
 version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(user_id,period_start),CHECK(period_end>period_start)
);
CREATE TABLE quota_hold (
 run_id uuid PRIMARY KEY,account_id uuid NOT NULL REFERENCES quota_account(id),price_id uuid NOT NULL REFERENCES model_price(id),
 tokens bigint NOT NULL CHECK(tokens>0),amount numeric(20,6) NOT NULL CHECK(amount>=0),
 state varchar(16) NOT NULL DEFAULT 'RESERVED' CHECK(state IN ('RESERVED','RECONCILING','SETTLED','RELEASED')),
 input_tokens bigint,output_tokens bigint,cached_tokens bigint,actual_amount numeric(20,6),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(input_tokens IS NULL OR input_tokens>=0),CHECK(output_tokens IS NULL OR output_tokens>=0),CHECK(cached_tokens IS NULL OR (cached_tokens>=0 AND cached_tokens<=input_tokens)),CHECK(actual_amount IS NULL OR actual_amount>=0)
);
CREATE INDEX quota_hold_account_idx ON quota_hold(account_id,state);
CREATE TABLE quota_ledger (
 id uuid PRIMARY KEY,account_id uuid NOT NULL REFERENCES quota_account(id),source_id uuid NOT NULL,
 kind varchar(20) NOT NULL CHECK(kind IN ('GRANT','RESERVE','SETTLE','RELEASE','RECONCILING')),
 grant_tokens bigint NOT NULL DEFAULT 0,reserve_tokens bigint NOT NULL DEFAULT 0,spend_tokens bigint NOT NULL DEFAULT 0,
 grant_amount numeric(20,6) NOT NULL DEFAULT 0,reserve_amount numeric(20,6) NOT NULL DEFAULT 0,spend_amount numeric(20,6) NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(account_id,source_id,kind)
);
CREATE INDEX quota_ledger_account_idx ON quota_ledger(account_id,created_at,id);
CREATE FUNCTION protect_quota_ledger() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'quota ledger is append only' USING ERRCODE='23514'; END; $$;
CREATE TRIGGER quota_ledger_immutable BEFORE UPDATE OR DELETE ON quota_ledger FOR EACH ROW EXECUTE FUNCTION protect_quota_ledger();
COMMENT ON TABLE quota_account IS '真实用量超出预占时仍完整入账，可用额度钳制为0；不能以余额不足丢弃供应商成本';
COMMENT ON TABLE quota_hold IS '未来模型run_id唯一预占；未知调用保留RECONCILING冻结，不能因断线重试或释放';
