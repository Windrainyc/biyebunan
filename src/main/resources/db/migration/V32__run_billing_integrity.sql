-- 预占必须属于真实运行且使用相同价格版本。历史孤立预占需人工核对，迁移不自动删除财务数据。
ALTER TABLE generation_run ADD CONSTRAINT run_price_pair UNIQUE(id,price_id);
ALTER TABLE quota_hold ADD CONSTRAINT quota_hold_run_price_fk FOREIGN KEY(run_id,price_id) REFERENCES generation_run(id,price_id);
-- 永久消息幂等、固定归属及价格快照不得被更新绕过。
CREATE FUNCTION protect_run_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF ROW(NEW.student_id,NEW.conversation_id,NEW.client_message_id,NEW.request_hash,NEW.model_id,NEW.price_id)
    IS DISTINCT FROM ROW(OLD.student_id,OLD.conversation_id,OLD.client_message_id,OLD.request_hash,OLD.model_id,OLD.price_id)
 THEN RAISE EXCEPTION 'run identity is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER run_identity_immutable BEFORE UPDATE ON generation_run FOR EACH ROW EXECUTE FUNCTION protect_run_identity();
