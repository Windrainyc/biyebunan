package cn.zhixiang.platform.notification.mapper;

import cn.zhixiang.platform.notification.entity.NotificationEntity;
import cn.zhixiang.platform.notification.entity.NotificationTaskEntity;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 收件箱持久化端口；所有外部查询显式绑定服务端收件人。 */
@Mapper
public interface NotificationMapper {
    /**
     * 收件人分页。
     *
     * @param user 服务端身份
     * @param q 分页搜索和封闭排序
     * @param unread 未读筛选，空表示全部
     * @return 当前页
     */
    @Select(
            "<script>SELECT id,title,body,read_at,created_at,(SELECT announcement_id FROM"
                + " announcement_recipient ar WHERE ar.event_id=in_app_notification.event_id AND"
                + " ar.recipient_id=in_app_notification.recipient_id) announcement_id FROM"
                + " in_app_notification WHERE recipient_id=#{user} AND (title ILIKE #{q.pattern}"
                + " ESCAPE E'\\\\' OR body ILIKE #{q.pattern} ESCAPE E'\\\\') <if test='unread !="
                + " null'>AND (read_at IS NULL)=#{unread}</if> ORDER BY ${q.orderBy} LIMIT"
                + " #{q.size} OFFSET #{q.offset}</script>")
    List<NotificationEntity> list(UUID user, @Param("q") PageQuery q, Boolean unread);

    /**
     * 与列表使用相同过滤条件计数。
     *
     * @param user 服务端身份
     * @param q 查询
     * @param unread 未读筛选
     * @return 总数
     */
    @Select(
            "<script>SELECT count(*) FROM in_app_notification WHERE recipient_id=#{user} AND (title"
                + " ILIKE #{q.pattern} ESCAPE E'\\\\' OR body ILIKE #{q.pattern} ESCAPE E'\\\\')"
                + " <if test='unread != null'>AND (read_at IS NULL)=#{unread}</if></script>")
    long count(UUID user, @Param("q") PageQuery q, Boolean unread);

    /**
     * 获取当前用户通知并锁定，外部不存在与越权统一为空。
     *
     * @param id 通知
     * @param user 用户
     * @return 通知或空
     */
    @Select(
            "SELECT id,title,body,read_at,created_at,(SELECT announcement_id FROM"
                + " announcement_recipient ar WHERE ar.event_id=in_app_notification.event_id AND"
                + " ar.recipient_id=in_app_notification.recipient_id) announcement_id FROM"
                + " in_app_notification WHERE id=#{id} AND recipient_id=#{user} FOR UPDATE")
    NotificationEntity lock(UUID id, UUID user);

    /**
     * 单向标记已读，不重复改变时间。
     *
     * @param id 已经锁定且验权的通知
     */
    @Update(
            "UPDATE in_app_notification SET read_at=now(),updated_at=now() WHERE id=#{id} AND"
                    + " read_at IS NULL")
    void markRead(UUID id);

    /**
     * 同事件去重；事件载荷冲突由服务层读取比较。
     *
     * @param id 新标识
     * @param user 收件人
     * @param event 事件标识
     * @param title 安全短标题
     * @param body 安全短正文
     */
    @Insert(
            "INSERT INTO in_app_notification(id,recipient_id,event_id,title,body)"
                    + " VALUES(#{id},#{user},#{event},#{title},#{body}) ON"
                    + " CONFLICT(recipient_id,event_id) DO NOTHING")
    void insert(UUID id, UUID user, UUID event, String title, String body);

    /**
     * 读取去重后的事件通知。
     *
     * @param user 收件人
     * @param event 事件
     * @return 已写入通知
     */
    @Select(
            "SELECT id,title,body,read_at,created_at,(SELECT announcement_id FROM"
                + " announcement_recipient ar WHERE ar.event_id=in_app_notification.event_id AND"
                + " ar.recipient_id=in_app_notification.recipient_id) announcement_id FROM"
                + " in_app_notification WHERE recipient_id=#{user} AND event_id=#{event}")
    NotificationEntity event(UUID user, UUID event);

    /**
     * 发布事务追加待投递任务，不获取收件人外键锁。
     *
     * @param user 接收者快照
     * @param event 领域事件
     * @param title 安全标题
     * @param body 安全正文
     */
    @Insert(
            "INSERT INTO notification_delivery_task(event_id,recipient_id,title,body)"
                    + " VALUES(#{event},#{user},#{title},#{body}) ON CONFLICT DO NOTHING")
    void enqueue(UUID user, UUID event, String title, String body);

    /**
     * 读取事件快照，检测错误的同键不同文案。
     *
     * @param user 接收者
     * @param event 领域事件
     * @return 已入队任务
     */
    @Select(
            "SELECT event_id,recipient_id,title,body FROM notification_delivery_task WHERE"
                    + " event_id=#{event} AND recipient_id=#{user}")
    NotificationTaskEntity task(UUID user, UUID event);

    /**
     * 多worker跳过其他事务占用的任务，数据库事务本身充当短租约。
     *
     * @return 一个已锁任务，空表示当前无可领取项
     */
    @Select(
            "SELECT event_id,recipient_id,title,body FROM notification_delivery_task WHERE"
                    + " state='QUEUED' ORDER BY created_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED")
    NotificationTaskEntity pending();

    /**
     * 判定接收者是否仍存在，不依赖客户端。
     *
     * @param user 接收者
     * @return 是否存在
     */
    @Select("SELECT EXISTS(SELECT 1 FROM user_account WHERE id=#{user})")
    boolean recipientExists(UUID user);

    /**
     * 与收件箱写入同事务确认任务。
     *
     * @param user 接收者
     * @param event 事件
     * @param failed 接收者已不存在
     */
    @Update(
            "UPDATE notification_delivery_task SET state=CASE WHEN #{failed} THEN 'FAILED' ELSE"
                + " 'DELIVERED' END,error_code=CASE WHEN #{failed} THEN 'RECIPIENT_MISSING' ELSE"
                + " NULL END,finished_at=now() WHERE event_id=#{event} AND recipient_id=#{user}")
    void delivered(UUID user, UUID event, boolean failed);

    /**
     * 追加受控渲染快照；禁用或未知渲染器以失败记录保留原因。
     *
     * @param user 接收者
     * @param event 领域事件
     * @param title 固定安全标题
     * @param body 固定安全正文
     * @param template 模板标识，可空表示内置
     * @param version 配置版本，可空
     * @param renderer 受控渲染版本
     * @param error 安全错误码，可空
     */
    @Insert(
            "INSERT INTO"
                + " notification_delivery_task(event_id,recipient_id,title,body,template_id,template_version,renderer_code,state,error_code,finished_at)"
                + " VALUES(#{event},#{user},#{title},#{body},#{template},#{version},#{renderer},CASE"
                + " WHEN CAST(#{error} AS text) IS NULL THEN 'QUEUED' ELSE 'FAILED'"
                + " END,#{error},CASE WHEN CAST(#{error} AS text) IS NULL THEN NULL ELSE now() END)"
                + " ON CONFLICT DO NOTHING")
    void enqueueTemplate(
            UUID user,
            UUID event,
            String title,
            String body,
            UUID template,
            Long version,
            String renderer,
            String error);

    /**
     * 原始摘要比较在正文清理后仍有效，禁止以不同文案重用事件键。
     *
     * @param user 收件人
     * @param event 事件
     * @param title 原始标题
     * @param body 原始正文
     * @return 是否与原始通知相同
     */
    @Select(
            "SELECT content_digest=encode(sha256(convert_to(jsonb_build_array(CAST(#{title} AS"
                    + " text),CAST(#{body} AS text))::text,'UTF8')),'hex') FROM in_app_notification"
                    + " WHERE recipient_id=#{user} AND event_id=#{event}")
    boolean sameContent(UUID user, UUID event, String title, String body);

    /**
     * 任务原始摘要比较，保留期不解除业务幂等约束。
     *
     * @param user 收件人
     * @param event 事件
     * @param title 原始标题
     * @param body 原始正文
     * @return 是否与原始任务相同
     */
    @Select(
            "SELECT content_digest=encode(sha256(convert_to(jsonb_build_array(CAST(#{title} AS"
                + " text),CAST(#{body} AS text))::text,'UTF8')),'hex') FROM"
                + " notification_delivery_task WHERE recipient_id=#{user} AND event_id=#{event}")
    boolean sameTaskContent(UUID user, UUID event, String title, String body);

    /**
     * 锁定指定可信事件尚未投递的任务；与补偿轮询竞争时等待其短事务结果。
     *
     * @param event 本库outbox事件
     * @return 待处理收件人任务
     */
    @Select(
            "SELECT event_id,recipient_id,title,body FROM notification_delivery_task WHERE"
                    + " event_id=#{event} AND state='QUEUED' ORDER BY recipient_id FOR UPDATE")
    List<NotificationTaskEntity> pendingEvent(UUID event);

    /**
     * 独立通知消费者去重，与站内信写入同事务。
     *
     * @param event 可信事件
     * @param digest 原始消息摘要
     * @return 首次写入为1，重复为0
     */
    @Insert(
            "INSERT INTO inbox_event(event_id,consumer,body_sha256)"
                    + " VALUES(#{event},'notification-v1',#{digest}) ON CONFLICT DO NOTHING")
    int receiveEvent(UUID event, String digest);

    /**
     * 已接收的消息摘要，拒绝同事件不同正文。
     *
     * @param event 事件标识
     * @return 摘要或空
     */
    @Select(
            "SELECT body_sha256 FROM inbox_event WHERE event_id=#{event} AND"
                    + " consumer='notification-v1'")
    String eventDigest(UUID event);
}
