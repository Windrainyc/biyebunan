-- 只登记资产拥有的精确退休对象键；不按用户输入前缀批量删除。
CREATE TABLE asset_object_gc (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),asset_id uuid NOT NULL REFERENCES asset(id),
 storage_id uuid NOT NULL REFERENCES storage_config(id),object_key varchar(512) NOT NULL,
 kind varchar(16) NOT NULL CHECK(kind IN ('UPLOAD','PRIVATE','ATTEMPT')),
 attempt_generation bigint, generation bigint NOT NULL DEFAULT 0,lease_until timestamptz,next_attempt_at timestamptz NOT NULL DEFAULT now(),
 attempts integer NOT NULL DEFAULT 0,completed_at timestamptz,error_code varchar(80),created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(storage_id,object_key)
);
CREATE INDEX asset_gc_poll_idx ON asset_object_gc(next_attempt_at,lease_until);
COMMENT ON TABLE asset_object_gc IS '只清理已过上传宽限期的源对象或软删除超过七天的无引用私有对象；每日复查处理崩溃后迟到写入';
