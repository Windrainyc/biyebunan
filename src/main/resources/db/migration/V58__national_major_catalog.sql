-- 全国专业目录不代表院校开设关系；保留历史专业及引用，新增全国代码单独唯一。
ALTER TABLE major ALTER COLUMN school_id DROP NOT NULL;
CREATE UNIQUE INDEX major_national_code_unique ON major(code) WHERE school_id IS NULL;
-- 旧复合外键不允许全国专业与院校独立组合，替换为专业自身外键；院校自身外键保留。
ALTER TABLE software_order DROP CONSTRAINT order_major_school_fk;
DO $$ DECLARE c record; BEGIN
  FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='user_account'::regclass
    AND confrelid='major'::regclass AND contype='f'
  LOOP EXECUTE format('ALTER TABLE user_account DROP CONSTRAINT %I', c.conname); END LOOP;
END $$;
ALTER TABLE user_account ADD CONSTRAINT user_account_major_reference FOREIGN KEY(major_id) REFERENCES major(id) ON DELETE RESTRICT;
COMMENT ON COLUMN major.school_id IS 'NULL为全国独立专业目录；非空为历史院校专业，不自动推断开设关系';
