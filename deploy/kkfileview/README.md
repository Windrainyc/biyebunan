# 知项私有 Office 转换服务

固定上游：kkFileView v5.0.3，commit `3b82689a676e8a7e7d3ec4cc0c74de0676b0dac9`（Apache-2.0）。适配代码为 src/main/java/cn/keking/web/controller/PrivateConversionController.java。已在本机隔离环境部署并完成DOCX/RTF真实转换与浏览器PDF预览；其他列入允许范围的Office格式尚未逐一验收。

只使用内部 POST /internal/convert 字节转换接口，不让浏览器访问 kkFileView，不传签名对象URL，不使用其公开转换缓存。每个请求使用随机临时目录、最多20MiB输入/输出、最多2项并发；禁止宏及外部链接自动更新，结束后清理临时文件。API与转换器共享的随机令牌从受保护文件/环境加载，不提交Git。

环境：转换器ZX_CONVERSION_TOKEN；API ZX_DOCUMENT_PREVIEW_ENDPOINT和ZX_DOCUMENT_PREVIEW_TOKEN_FILE。未配置时API明确503。转换器只应加入内部Docker网络；仅映射宿主机回环端口18790供本机API调用，不允许外部网络出口。LibreOffice任务执行超时应设为60秒，API总等待90秒。

第一版采用即时有界PDF响应，继续使用系统PDF.js，不新增公开副本。将来高并发改为持久任务、私有副本和回收登记，不能把kk缓存当作权限边界。

采用Office专用裁剪构建，复用上游OfficePluginManager、LocalOfficeUtils、ConfigUtils，保留Apache许可；不包含CAD、压缩包、上游网页UI和视频转码组件。转换调用JODConverter 4.4.11，宏和外链更新均禁用。此构建不是kkFileView完整通用预览发行版。

启动：`python3 scripts/start-office-preview.py`（从后端工程运行）。停止只用 `docker --context colima-zhixiang compose -p zhixiang-office -f deploy/kkfileview/compose.yaml stop`，不删除数据卷。

网关仅绑定127.0.0.1:18790并转发固定路径；转换容器只连接internal网络，不连接网关的外部网络。已验证通用路径404、错误令牌403。当前镜像内LibreOffice包版本1:7.3.7-0ubuntu0.22.04.13，基础镜像固定摘要；后续升级需重跑文档兼容验证。
