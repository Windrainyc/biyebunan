-- 价格记录为运行费用快照的源；已生效记录不可修改金额，运行表必须外键引用。
CREATE TABLE model_price (
 id uuid PRIMARY KEY,model_id uuid NOT NULL REFERENCES model_definition(id) ON DELETE RESTRICT,
 currency varchar(3) NOT NULL CHECK(currency='CNY'),
 input_per_million numeric(20,6) NOT NULL CHECK(input_per_million>=0),
 output_per_million numeric(20,6) NOT NULL CHECK(output_per_million>=0),
 cached_input_per_million numeric(20,6) NOT NULL CHECK(cached_input_per_million>=0),
 effective_from timestamptz NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('ENABLED','DISABLED')),version bigint NOT NULL DEFAULT 1,
 created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(model_id,effective_from)
);
CREATE INDEX model_price_selection_idx ON model_price(model_id,status,effective_from DESC);
COMMENT ON TABLE model_price IS '同模型按effective_from选择最新可用价格，已开始运行必须固定price_id及金额快照';
