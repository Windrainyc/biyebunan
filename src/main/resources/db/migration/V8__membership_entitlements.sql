-- 会员套餐与不可变权益版本；金额固定六位小数，历史会员不跟随套餐配置变化。
CREATE TABLE membership_plan (
 id uuid PRIMARY KEY, name varchar(200) NOT NULL, code varchar(200) NOT NULL UNIQUE,
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')), version bigint NOT NULL DEFAULT 1,
 created_by uuid NOT NULL REFERENCES user_account(id), updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE membership_plan_version (
 id uuid PRIMARY KEY, plan_id uuid NOT NULL REFERENCES membership_plan(id) ON DELETE CASCADE,
 version_number bigint NOT NULL CHECK(version_number>0), duration_days integer NOT NULL CHECK(duration_days BETWEEN 1 AND 36500),
 monthly_token_quota bigint NOT NULL CHECK(monthly_token_quota>=0), monthly_amount_limit numeric(20,6) NOT NULL CHECK(monthly_amount_limit>=0),
 effective_from timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(plan_id,version_number), UNIQUE(plan_id,effective_from), UNIQUE(id,plan_id)
);
CREATE INDEX membership_version_effective_idx ON membership_plan_version(plan_id,effective_from DESC);
CREATE TABLE membership (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES user_account(id),plan_id uuid NOT NULL REFERENCES membership_plan(id),
 plan_version_id uuid NOT NULL, starts_at timestamptz NOT NULL,expires_at timestamptz NOT NULL,
 revoked_at timestamptz,version bigint NOT NULL DEFAULT 1,created_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(plan_version_id,plan_id) REFERENCES membership_plan_version(id,plan_id) ON DELETE RESTRICT,
 CHECK(expires_at>starts_at)
);
CREATE INDEX membership_user_period_idx ON membership(user_id,starts_at,expires_at) WHERE revoked_at IS NULL;
COMMENT ON TABLE membership IS '账户锁串行检查未撤销期间不重叠；撤销保留历史，ACTIVE/SCHEDULED/EXPIRED按服务端当前时间投影';
COMMENT ON TABLE membership_plan_version IS '只追加不覆盖；授予时固定当前已生效权益版本，未来配置不会改变既有会员';
