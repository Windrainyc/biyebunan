# 知项平台后端

开发中的项目答疑平台，Java21、Spring Boot4.0.8、MyBatis、PostgreSQL，复用独立Redis/RabbitMQ/MinIO环境。当前实现认证与权限、院校专业、会员、菜单、知识分类标签、模型价格密钥、存储域名配置、订单草稿及数据范围、outbox发布。整个后端尚未完成。

[开发进度与接续](docs/开发进度.md) · [接口覆盖](docs/接口实现覆盖矩阵.md) · [本轮验证](docs/本轮验证-20261026凌晨.md) · [初始化管理员](docs/管理员初始化与邀请.md)

截至2026-10-26凌晨，43项完整本地测试通过，171/243接口已映射；本地V30迁移和0.8.0-dev镜像运行通过。测试账户仅存在于隔离schema，正式环境没有默认账户。镜像验证容器已经停止，中间件保留。

## 本地运行

```sh
mvn -s scripts/maven-settings.xml -B -ntp package
python3 scripts/run-local.py migrate
python3 scripts/run-local.py api
# 另一个终端启动异步worker
python3 scripts/run-local.py worker
# 完整隔离中间件测试
python3 scripts/run-local.py test
```

API为127.0.0.1:18080。脚本仅从受保护文件读取凭证，不将凭证写入仓库。Docker构建用`docker --context colima-zhixiang build -t zhixiang/platform:0.8.0-dev .`，随后运行`python3 scripts/verify-container.py`验证并清理自身容器。

已接入订单一次性绑定、项目主体/里程碑、中介进度摘要和真实MinIO端口。本轮新增资产隔离扫描流程、私有下载、全额收款核验、不可变交付版本与项目阶段/学生验收。尚缺真实扫描服务部署、对象GC、进行中取消补偿、知识向量召回、模型运行/SSE与账务对账、论坛及统计等。保存模型/存储/域名配置不代表对应外部服务已验证或部署。

[Spring Boot系统要求](https://docs.spring.io/spring-boot/4.0/system-requirements.html) · [MyBatis兼容范围](https://github.com/mybatis/spring-boot-starter)。运行时镜像锁定digest；未实施生产多节点部署。

[资产与交付运行说明](docs/资产与交付运行说明.md)；默认未配置扫描服务时文件保持隔离。

本轮新增知识内容/发布快照/Markdown关键词检索、任务恢复与事件统计inbox。详见[知识库与任务运行说明](docs/知识库与任务运行说明.md)；向量检索及文档/视频正文抽取未实现。

本轮加入学生会话与真实额度账本、OpenAI兼容文本流端口；详见[会话与额度运行说明](docs/会话与额度运行说明.md)。模型调用默认关闭，消息运行创建尚未开放；SSE与历史读取能力见下方阶段说明。

[流事件与运行结束说明](docs/流事件与运行结束说明.md)：已实现持久SSE与消息历史、运行结束原子计费；生成任务创建和完整调用编排仍待接通。
