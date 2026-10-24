-- 订单主体与角色数据范围；绝不依赖客户端传入角色或归属过滤。
CREATE TABLE software_order (
 id uuid PRIMARY KEY,order_no varchar(200) NOT NULL UNIQUE,title varchar(200) NOT NULL,
 student_id uuid REFERENCES user_account(id) ON DELETE RESTRICT,
 partner_id uuid REFERENCES user_account(id) ON DELETE RESTRICT,
 student_phone_digest varchar(128) NOT NULL,student_phone_ciphertext text NOT NULL,
 amount numeric(20,6) NOT NULL CHECK(amount>=0),currency varchar(3) NOT NULL CHECK(currency='CNY'),
 package_code varchar(200) NOT NULL,source varchar(16) NOT NULL CHECK(source IN ('MANUAL','IMPORT','PARTNER')),
 school_id uuid NOT NULL REFERENCES school(id) ON DELETE RESTRICT,major_id uuid NOT NULL REFERENCES major(id) ON DELETE RESTRICT,
 note varchar(200) NOT NULL DEFAULT '',status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','CONFIRMED','ACTIVE','COMPLETED','CANCELLED','ARCHIVED')),
 receipt_status varchar(16) NOT NULL DEFAULT 'UNCONFIRMED' CHECK(receipt_status IN ('UNCONFIRMED','CONFIRMED')),
 version bigint NOT NULL DEFAULT 1,created_by uuid NOT NULL REFERENCES user_account(id),updated_by uuid NOT NULL REFERENCES user_account(id),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX order_partner_page_idx ON software_order(partner_id,status,created_at DESC,id DESC);
CREATE INDEX order_student_page_idx ON software_order(student_id,status,created_at DESC,id DESC);
CREATE INDEX order_admin_page_idx ON software_order(status,created_at DESC,id DESC);
COMMENT ON TABLE software_order IS '业务历史不物理删除；中介按partner_id，学生按student_id过滤。手机号AAD绑定订单ID';
-- 防止专业后续转移院校导致订单的院校/专业组合失效，不能仅靠写入时校验。
ALTER TABLE major ADD CONSTRAINT major_id_school_unique UNIQUE(id,school_id);
ALTER TABLE software_order ADD CONSTRAINT order_major_school_fk FOREIGN KEY(major_id,school_id) REFERENCES major(id,school_id) ON DELETE RESTRICT;
