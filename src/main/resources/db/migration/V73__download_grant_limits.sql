-- 只统计签发次数，不将签名次数冒充实际 OSS 流量。
CREATE TABLE download_grant_counter (
 counter_key varchar(160) NOT NULL, window_start bigint NOT NULL,
 used bigint NOT NULL CHECK(used>0), expires_at timestamptz NOT NULL,
 PRIMARY KEY(counter_key,window_start)
);
CREATE INDEX download_grant_counter_expiry_idx ON download_grant_counter(expires_at);
