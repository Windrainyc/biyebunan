# 知项平台工程规范

本仓库独立于jialework和界面原型；所有修改仅限当前工程及已授权交付目录。

## Java代码

- Java 21、Spring Boot、MyBatis；保持现有真实业务实现，不用空方法或演示成功替代。
- 使用 `python3 scripts/format-java.py --write` 格式化，Google Java Format 1.22.0 AOSP四空格规则。禁止将多个字段、方法、条件分支压在一行。
- 业务模块下按职责分包：controller、service、mapper、dto、entity；配置放config，外部端口放port，实现放adapter，后台执行放worker。启动类留根包。
- Controller只处理HTTP与校验并调用Service，不能直接注入Mapper。Service组织业务和事务；Mapper只访问数据。
- 请求和响应记录放dto，持久化与查询记录放entity，不将公开记录类型嵌入Controller、Service、Mapper。私有实现细节记录可以留在所属类。
- 公共类、接口、方法、字段及record参数提供中文JavaDoc；说明业务约束、单位、状态、锁顺序及异常，不逐行复述代码。
- 数据库变更使用新增Flyway迁移，禁止修改已经应用的迁移。
- 提交前运行格式、分层、必要业务测试、契约检查和严格JavaDoc；包调整还需验证启动与扫描。

## Git提交

- 提交标题使用中文，清楚说明改动。示例：`修复：拒绝已撤销权限的导出任务`、`新增：模型配置只读探测接口`、`重构：规范Java目录分层与代码格式`。
- 本地启用 `git config --local core.hooksPath .githooks`；commit-msg自动拒绝没有中文说明的标题。
- 不推送远程，不擅自重写历史提交或覆盖用户工作。

## 工作顺序与额度

先完成后端范围，再用Vue3按确认原型实现独立管理员、学生、中介平台，接真实API，最后启动全栈并验证浏览器主要流程。
每个阶段检查额度；任何可用窗口剩余<=5%时保存开发进度，并更新既有automation-5到受限窗口最晚恢复后三分钟。不创建重复任务，不将接口映射比例当作交付完成率。
