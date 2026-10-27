# OSS回调验签与隔离

POST /api/v1/callbacks/oss 使用独立供应商签名，不接受用户Bearer令牌作为回调授权。原始请求体上限16KiB，按官方MD5withRSA协议校验固定路径、原始查询串和原始消息字节，验签前不重排表单。

配置ZX_OSS_CALLBACK_PUBLIC_KEY_FILE指向部署可信的X.509 PEM公钥文件，ZX_OSS_CALLBACK_KEY_URL指定对应官方HTTPS来源，默认https://gosspublic.alicdn.com/callback_pub_key_v1.pem。请求头x-oss-pub-key-url解码后须完全匹配该来源；服务器不会向请求给定的URL发网络请求。部署人员需从官方来源获取并维护公钥，轮换时更新文件并滚动重启。未配置公钥明确503。

表单仅接受uploadId、bucket、object、size、etag，禁止重复或额外字段。会话、隔离键、大小、供应商、桶和配置启停必须匹配；对象HEAD在事务外完成，ETag与长度必须相符。资产行锁内再次检查并将资产转为QUARANTINED，登记唯一回调摘要、扫描任务和审计，同事务提交。相同通知重放返回当前资产状态，不重复副作用；不同原文重用上传会话返回409。下载仍须等待SHA256验真、扫描和私有副本发布，回调不直接设置READY。

## 验证与未配置能力

隔离RSA协议测试覆盖原文/查询串篡改、替换公钥来源、Bearer令牌拒绝。真实PostgreSQL测试覆盖隔离状态、唯一回调/任务/审计和重复请求；该测试的OSS HEAD端口使用确定性替身。当前生产对象存储选型未确认，本地仍用真实MinIO；ALIYUN_OSS对象适配未配置，真实OSS回调将明确失败，不能将接口存在视为云端已接通。未创建云资源或发起付费调用。

官方协议参考：[阿里云OSS Callback](https://www.alibabacloud.com/help/en/oss/developer-reference/callback)。本实现将官方可选验签收紧为必需，采用部署固定公钥缓存而不是请求时下载任意地址。
