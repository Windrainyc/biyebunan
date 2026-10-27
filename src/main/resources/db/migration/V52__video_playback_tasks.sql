-- 独立播放副本不修改源资产及交付版本；进程崩溃通过租约重领，孤立输出由对象GC回收。
CREATE TABLE video_task (
 asset_id uuid PRIMARY KEY REFERENCES asset(id),
 state varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','SKIPPED')),
 generation bigint NOT NULL DEFAULT 0,lease_until timestamptz,attempts integer NOT NULL DEFAULT 0,
 output_key varchar(512),object_version varchar(300),output_size bigint,
 duration_seconds double precision,width integer,height integer,error_code varchar(100),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX video_task_poll_idx ON video_task(state,created_at);
ALTER TABLE asset_object_gc DROP CONSTRAINT asset_object_gc_kind_check;
ALTER TABLE asset_object_gc ADD CONSTRAINT asset_object_gc_kind_check CHECK(kind IN ('UPLOAD','PRIVATE','ATTEMPT','MEDIA'));
COMMENT ON TABLE video_task IS '已扫描私有视频的独立H264/AAC播放副本；任务成功不能修改源文件';
