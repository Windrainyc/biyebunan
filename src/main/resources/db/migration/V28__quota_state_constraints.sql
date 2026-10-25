-- 把预占状态与实际用量的关系落实到数据库，避免未来管理工具绕过服务层写入半结算记录。
ALTER TABLE quota_hold ADD CONSTRAINT quota_hold_usage_state CHECK (
 (state IN ('RESERVED','RECONCILING') AND input_tokens IS NULL AND output_tokens IS NULL AND cached_tokens IS NULL AND actual_amount IS NULL)
 OR (state='SETTLED' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL AND cached_tokens IS NOT NULL AND actual_amount IS NOT NULL)
 OR (state='RELEASED' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL AND cached_tokens IS NOT NULL AND actual_amount IS NOT NULL
     AND input_tokens=0 AND output_tokens=0 AND cached_tokens=0 AND actual_amount=0)
);
-- 每种账本事件只允许自身定义的增量方向；历史数据不可修改，纠错必须有新的业务事件。
ALTER TABLE quota_ledger ADD CONSTRAINT quota_ledger_delta_shape CHECK (
 (kind='GRANT' AND grant_tokens>=0 AND grant_amount>=0 AND (grant_tokens>0 OR grant_amount>0)
  AND reserve_tokens=0 AND spend_tokens=0 AND reserve_amount=0 AND spend_amount=0)
 OR (kind='RESERVE' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens>0 AND reserve_amount>=0 AND spend_tokens=0 AND spend_amount=0)
 OR (kind='SETTLE' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens<0 AND reserve_amount<=0 AND spend_tokens>=0 AND spend_amount>=0)
 OR (kind='RELEASE' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens<0 AND reserve_amount<=0 AND spend_tokens=0 AND spend_amount=0)
 OR (kind='RECONCILING' AND grant_tokens=0 AND grant_amount=0 AND reserve_tokens=0 AND reserve_amount=0 AND spend_tokens=0 AND spend_amount=0)
);
