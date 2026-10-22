# 知项平台后端

企业级项目答疑平台后端，正在开发，尚未完成。Java 21、Spring Boot 4.0.8、MyBatis、PostgreSQL；复用独立目录中的 Redis/RabbitMQ/MinIO 本地环境。

已实现院校/专业管理纵向流程和身份授权基础，完整状态、验证结果、启动命令及后续任务见 [开发进度](docs/开发进度.md)。本轮隔离数据库测试通过，本地正式 schema 迁移尚待处理，不能视作可交付的完整后台。

依赖依据：[Spring Boot 4.0 系统要求](https://docs.spring.io/spring-boot/4.0/system-requirements.html)、[MyBatis Spring Boot 兼容范围](https://github.com/mybatis/spring-boot-starter)。后续上线前仍需依赖漏洞扫描和补丁版本复核。
