-- 批次标识仅允许在未发布、尚未归组的快照上赋值一次，发布后不可改写。
CREATE FUNCTION protect_delivery_release() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.release_id,NEW.release_number) IS DISTINCT FROM ROW(OLD.release_id,OLD.release_number)
 AND NOT (OLD.state='READY' AND NEW.state='READY' AND OLD.release_id IS NULL AND OLD.release_number IS NULL AND NEW.release_id IS NOT NULL AND NEW.release_number>0)
 THEN RAISE EXCEPTION 'delivery release is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER delivery_release_immutable BEFORE UPDATE ON project_delivery FOR EACH ROW EXECUTE FUNCTION protect_delivery_release();
