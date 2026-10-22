-- 为已上线前的基础数据补齐操作者字段；旧记录允许空，后续业务接口强制从认证身份写入。
ALTER TABLE school ADD COLUMN created_by uuid REFERENCES user_account(id), ADD COLUMN updated_by uuid REFERENCES user_account(id);
ALTER TABLE major ADD COLUMN created_by uuid REFERENCES user_account(id), ADD COLUMN updated_by uuid REFERENCES user_account(id);
COMMENT ON COLUMN school.created_by IS '创建人；历史迁移记录可空，禁止客户端指定';
COMMENT ON COLUMN school.updated_by IS '最后修改人，服务端认证身份';
COMMENT ON COLUMN major.created_by IS '创建人；历史迁移记录可空，禁止客户端指定';
COMMENT ON COLUMN major.updated_by IS '最后修改人，服务端认证身份';
