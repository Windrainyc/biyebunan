# 知项平台后端

Java 21、Spring Boot 4.0.8、MyBatis、PostgreSQL/pgvector，模块化单体，独立API与worker。三端权限隔离、基础配置、订单/绑定/项目交付、知识检索、模型SSE与账务、论坛、通知、统计导出、审计及任务恢复均有真实实现。前端后续使用Vue3按已确认原型接入。

[开发进度](docs/开发进度.md) · [接口覆盖](docs/接口实现覆盖矩阵.md) · [管理员初始化](docs/管理员初始化与邀请.md) · [代码规范](docs/Java代码与提交规范.md)

当前74项本地测试通过，247个接口操作映射及30类响应DTO静态契约检查通过。测试包含隔离PostgreSQL/Redis/RabbitMQ/MinIO、真实ClamAV/Tika/FFmpeg及隔离HTTP模型协议服务。路由映射不是全部状态或生产容量验收。真实模型、短信与云资源未开通，不提供默认生产账户。

## 本地运行

```sh
python3 scripts/run-local.py test
python3 scripts/run-local.py migrate
python3 scripts/run-local.py api
# 另一个终端启动异步worker
python3 scripts/run-local.py worker
```

API默认127.0.0.1:18080。配置从受保护本地环境读取，明文凭证不入库或提交。API镜像用Dockerfile，视频worker用Dockerfile.worker，二者分别构建；数据库、缓存、消息和对象存储是独立镜像及持久卷。scripts/verify-container.py验证当前镜像后只清理自身临时容器，不是持续启动脚本。

## 实现说明

- [模型运行与引用](docs/模型运行与引用说明.md)、[流事件与运行结束](docs/流事件与运行结束说明.md)、[人工用量裁决](docs/人工用量裁决说明.md)：持久运行、断线回放、原子预占结算、未知冻结及双人复核。
- [资产与交付](docs/资产与交付运行说明.md)、[对象回收与真实扫描](docs/对象回收与真实扫描说明.md)、[视频处理](docs/视频处理与播放说明.md)：私有上传、扫描隔离、版本交付、真实转码及延迟GC。
- [文档抽取](docs/文档抽取运行说明.md)、[向量检索](docs/向量索引与语义检索说明.md)：PDF/DOCX文本抽取、关键词及可配置语义检索。视频目前仅标题索引，无语音/画面转写。
- [订单取消](docs/订单取消与财务核对说明.md)、[论坛附件](docs/论坛附件与学生上传说明.md)、[通知模板](docs/通知模板渲染说明.md)、[通知保留](docs/请求关联与通知保留说明.md)。
- [链路与指标](docs/链路追踪与指标说明.md)、[死信恢复](docs/死信受控恢复说明.md)、[OSS回调](docs/OSS回调验签与隔离说明.md)。

## 交付边界

本地已部署扫描和解析服务；未配置扫描的其他环境必须保持隔离。语义模型与模型答疑默认关闭，真实供应商质量、费用及账单查询能力需接入后验证。MinIO本地适配可用，阿里云OSS生产适配尚未启用；订单取消不代表外部退款。OTLP默认关闭，生产Collector、真实多节点集群、负载与灾备目标均不可视为已验收。剩余开发和验证以开发进度顶部为准，历史阶段文档保留原验证范围。
