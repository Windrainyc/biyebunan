-- pgvector由运维在public安装；应用账号不创建扩展。精确余弦检索先完成权限过滤，暂不承诺ANN容量。
CREATE TABLE knowledge_vector (
 chunk_id uuid NOT NULL REFERENCES knowledge_chunk(id) ON DELETE CASCADE,
 profile varchar(64) NOT NULL,
 dimensions integer NOT NULL CHECK(dimensions BETWEEN 1 AND 2000),
 embedding public.vector NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(chunk_id,profile),
 CHECK(public.vector_dims(embedding)=dimensions)
);
CREATE TABLE knowledge_embedding_task (
 chunk_id uuid NOT NULL REFERENCES knowledge_chunk(id) ON DELETE CASCADE,
 profile varchar(64) NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
 generation bigint NOT NULL DEFAULT 0,
 lease_until timestamptz,
 input_tokens bigint CHECK(input_tokens>=0),
 error_code varchar(100),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(chunk_id,profile)
);
CREATE INDEX knowledge_embedding_pending_idx ON knowledge_embedding_task(profile,state,created_at);
COMMENT ON TABLE knowledge_vector IS '模型配置指纹隔离的向量；当前发布快照/代次/权限仍由知识查询强制约束';
COMMENT ON COLUMN knowledge_embedding_task.input_tokens IS '供应商返回输入词元，未知为空；索引基础设施用量，不扣学生问答额度';
-- 嵌入是独立基础设施成本，保留已知用量或未知错误，不冒充学生聊天账单。
CREATE TABLE embedding_request_audit (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),profile varchar(64) NOT NULL,
 purpose varchar(8) NOT NULL CHECK(purpose IN ('INDEX','SEARCH')),
 actor_id uuid REFERENCES user_account(id),input_tokens bigint CHECK(input_tokens>=0),
 error_code varchar(100),created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX embedding_request_audit_profile_created_idx ON embedding_request_audit(profile,created_at);
COMMENT ON TABLE embedding_request_audit IS '嵌入调用已知词元及未知结果；不保存文本和密钥，不自动扣学生问答额度';
