-- 统计投影专用inbox，不代表计费、转码等其他消费者已经完成。
ALTER TABLE inbox_event ADD COLUMN body_sha256 char(64);
CREATE TABLE event_activity_day (
 day date NOT NULL,event_type varchar(200) NOT NULL,event_count bigint NOT NULL CHECK(event_count>0),
 PRIMARY KEY(day,event_type)
);
COMMENT ON TABLE inbox_event IS '与事件日聚合同事务提交；broker ACK在事务返回后执行，重复投递不得重复计数';
