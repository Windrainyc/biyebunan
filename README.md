# 知项平台后端

开发中的项目答疑平台，Java21、Spring Boot4.0.8、MyBatis、PostgreSQL，复用独立Redis/RabbitMQ/MinIO环境。当前实现院校专业管理、部分认证和outbox发布，尚未完成整个后端。

[开发进度与接续](docs/开发进度.md) 包含启动方式、真实测试结果、已实现范围和未完成项。最新10个测试通过；实际API及readiness健康UP；V1–V4迁移已执行。测试账户仅存在于隔离schema，正式环境没有默认账户。

[Spring Boot系统要求](https://docs.spring.io/spring-boot/4.0/system-requirements.html) · [MyBatis兼容范围](https://github.com/mybatis/spring-boot-starter)。镜像已锁运行时digest，生产上线前仍需完整依赖扫描、权限审查和集群演练。
