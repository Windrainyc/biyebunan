-- 交付内容不可更新；发布状态可迁移，项目锁串行分配同类别版本号。
CREATE TABLE project_delivery (
 id uuid PRIMARY KEY,project_id uuid NOT NULL REFERENCES software_project(id),
 kind varchar(32) NOT NULL CHECK(kind IN ('TASK','PROPOSAL','THESIS_TEMPLATE','SOURCE_CODE','QA_ATTACHMENT','RECORDING')),
 asset_id uuid NOT NULL REFERENCES asset(id),note varchar(1000) NOT NULL,
 version_number integer NOT NULL CHECK(version_number>0),
 state varchar(16) NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','PUBLISHED','SUPERSEDED','WITHDRAWN')),
 version bigint NOT NULL DEFAULT 1,created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(project_id,kind,version_number)
);
CREATE UNIQUE INDEX project_delivery_published_idx ON project_delivery(project_id,kind) WHERE state='PUBLISHED';
CREATE INDEX project_delivery_page_idx ON project_delivery(project_id,created_at DESC,id DESC);
CREATE INDEX project_delivery_asset_idx ON project_delivery(asset_id);
CREATE FUNCTION protect_delivery_content() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.project_id,NEW.kind,NEW.asset_id,NEW.note,NEW.version_number,NEW.created_by,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.project_id,OLD.kind,OLD.asset_id,OLD.note,OLD.version_number,OLD.created_by,OLD.created_at)
 THEN RAISE EXCEPTION 'delivery content is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER delivery_content_immutable BEFORE UPDATE ON project_delivery FOR EACH ROW EXECUTE FUNCTION protect_delivery_content();
COMMENT ON TABLE project_delivery IS '不可变交付快照；每类仅一个当前发布版，替代和撤回不回退版本号';
CREATE TABLE delivery_event (
 id uuid PRIMARY KEY,delivery_id uuid NOT NULL REFERENCES project_delivery(id),
 action varchar(32) NOT NULL,reason varchar(1000) NOT NULL,
 actor_id uuid NOT NULL REFERENCES user_account(id),created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX delivery_event_history_idx ON delivery_event(delivery_id,created_at,id);
