# Java代码与提交规范

按业务模块隔离，再按职责分层。以订单模块为例：

```text
order/
  controller/   HTTP入口和参数校验
  service/      业务规则、权限范围、事务编排
  mapper/       MyBatis数据访问
  dto/          请求、响应及查询条件
  entity/       持久化数据和查询投影
```

外部能力另设port与adapter，后台任务放worker，Spring配置放config，安全工具放security。测试与主代码同步更新包引用；为访问专用测试构造器的测试跟随适配器包。MyBatis扫描仍从应用根包递归发现Mapper，UUID类型处理器配置已更新。

本轮迁移274个现有顶层类，拆出59个Mapper记录至entity、14个公开请求/响应记录至dto。保留请求路径、权限代码、数据库表与状态语义；没有引入空Service接口或无意义继承。

## 自动检查

- 修复格式：`python3 scripts/format-java.py --write`。
- 仅检查格式：`python3 scripts/format-java.py`。
- 检查包路径及职责：`python3 scripts/check-java-layout.py`。
- 完整本地测试：`python3 scripts/run-local.py test`，会先执行以上两项检查。
- 严格注释：`python3 scripts/verify-javadoc.py`。

格式工具锁定Google Java Format 1.22.0、Guava 33.3.1-jre、failureaccess 1.0.2，使用本地Maven缓存，缺失时脚本给出明确安装命令。四空格缩进、方法/字段分行、统一换行与import整理。SQL注解长字符串由格式工具自动折行。

Git提交标题采用中文业务说明，本仓库commit-msg钩子阻止纯英文标题。已有历史提交保留，未重写提交链。后续提交示例：`修复：订单绑定竞争时返回明确冲突`。
