package cn.zhixiang.platform.notification.mapper;

import cn.zhixiang.platform.notification.dto.AnnouncementDtos;
import cn.zhixiang.platform.notification.entity.*;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 发布、接收者快照及发送统计数据端口。 */
@Mapper
public interface AnnouncementMapper {
    /**
     * 创建不可变发布记录。
     *
     * @param id 公告标识
     * @param w 已校验发布输入
     * @param actor 真实管理员
     */
    @Insert(
            "INSERT INTO announcement(id,title,body,kind,audience,created_by)"
                    + " VALUES(#{id},#{w.title},#{w.body},#{w.kind},#{w.audience},#{actor})")
    void insert(UUID id, AnnouncementDtos.Publish w, UUID actor);

    /**
     * 在一条语句快照当前启用收件人。
     *
     * @param id 公告标识
     * @param selected 是否指定范围
     * @param ids 非空指定ID或全部范围空列表
     * @return 快照人数
     */
    @Insert(
            "<script>INSERT INTO announcement_recipient(announcement_id,recipient_id,event_id)"
                + " SELECT #{id},id,gen_random_uuid() FROM user_account WHERE status='ENABLED'<if"
                + " test='selected'> AND id IN <foreach collection='ids' item='u' open='('"
                + " separator=',' close=')'>#{u}</foreach></if></script>")
    int snapshot(UUID id, boolean selected, List<UUID> ids);

    /**
     * 每个收件人生成独立可信事件。
     *
     * @param id 公告标识
     */
    @Insert(
            "INSERT INTO outbox_event(id,aggregate_id,event_type,payload) SELECT"
                + " event_id,announcement_id,'announcement.PUBLISHED',jsonb_build_object('id',announcement_id)"
                + " FROM announcement_recipient WHERE announcement_id=#{id}")
    void events(UUID id);

    /**
     * 原子登记投递任务供Rabbit与补偿处理。
     *
     * @param id 公告标识
     * @param title 标题快照
     * @param summary 最多200字摘要
     */
    @Insert(
            "INSERT INTO notification_delivery_task(event_id,recipient_id,title,body) SELECT"
                    + " event_id,recipient_id,#{title},#{summary} FROM announcement_recipient WHERE"
                    + " announcement_id=#{id}")
    void tasks(UUID id, String title, String summary);

    /**
     * 查询固定正文。
     *
     * @param id 公告标识
     * @return 公告或空
     */
    @Select("SELECT id,title,body,kind,audience,created_at FROM announcement WHERE id=#{id}")
    AnnouncementEntity get(UUID id);

    /**
     * 验证详情接收范围。
     *
     * @param id 公告标识
     * @param user 真实用户
     * @return 是否在快照内
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM announcement_recipient WHERE announcement_id=#{id} AND"
                    + " recipient_id=#{user})")
    boolean receives(UUID id, UUID user);

    /**
     * 统计快照人数。
     *
     * @param id 公告标识
     * @return 人数
     */
    @Select("SELECT count(*) FROM announcement_recipient WHERE announcement_id=#{id}")
    long recipients(UUID id);

    /**
     * 统计已落库人数。
     *
     * @param id 公告标识
     * @return 人数
     */
    @Select(
            "SELECT count(*) FROM announcement_recipient ar JOIN in_app_notification n ON"
                    + " n.event_id=ar.event_id AND n.recipient_id=ar.recipient_id WHERE"
                    + " ar.announcement_id=#{id}")
    long delivered(UUID id);

    /**
     * 统计真实已读人数。
     *
     * @param id 公告标识
     * @return 人数
     */
    @Select(
            "SELECT count(*) FROM announcement_recipient ar JOIN in_app_notification n ON"
                    + " n.event_id=ar.event_id AND n.recipient_id=ar.recipient_id WHERE"
                    + " ar.announcement_id=#{id} AND n.read_at IS NOT NULL")
    long read(UUID id);

    /**
     * 发送记录分页。
     *
     * @param q 已校验分页和字面搜索
     * @return 当前页
     */
    @Select(
            "SELECT id,title,body,kind,audience,created_at FROM announcement WHERE title ILIKE"
                    + " #{q.pattern} ORDER BY created_at DESC,id DESC LIMIT #{q.size} OFFSET"
                    + " #{q.offset}")
    List<AnnouncementEntity> list(@Param("q") PageQuery q);

    /**
     * 发送记录计数。
     *
     * @param q 同列表筛选
     * @return 总数
     */
    @Select("SELECT count(*) FROM announcement WHERE title ILIKE #{q.pattern}")
    long count(@Param("q") PageQuery q);

    /**
     * 选择启用接收用户，不返回真实手机。
     *
     * @param q 分页搜索
     * @param platform 可选平台过滤
     * @return 当前页
     */
    @Select(
            "SELECT id,display_name,platform,phone_masked FROM user_account WHERE status='ENABLED'"
                + " AND (#{platform}::varchar IS NULL OR platform=#{platform}) AND (display_name"
                + " ILIKE #{q.pattern} OR phone_masked ILIKE #{q.pattern}) ORDER BY created_at"
                + " DESC,id DESC LIMIT #{q.size} OFFSET #{q.offset}")
    List<AnnouncementRecipientEntity> users(@Param("q") PageQuery q, String platform);

    /**
     * 接收用户查询计数。
     *
     * @param q 分页搜索
     * @param platform 可选平台过滤
     * @return 总数
     */
    @Select(
            "SELECT count(*) FROM user_account WHERE status='ENABLED' AND (#{platform}::varchar IS"
                    + " NULL OR platform=#{platform}) AND (display_name ILIKE #{q.pattern} OR"
                    + " phone_masked ILIKE #{q.pattern})")
    long userCount(@Param("q") PageQuery q, String platform);
}
