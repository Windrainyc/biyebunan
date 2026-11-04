package cn.zhixiang.platform.operations.mapper;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.operations.dto.ActivityDtos;
import cn.zhixiang.platform.operations.entity.ActivityEntity;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.*;

/** 活动数据持久层，列表与总数使用相同范围。 */
@Mapper
public interface UserActivityMapper {
    /** 共用查询过滤。 */
    String FILTER =
            " FROM (SELECT id,actor_id,platform,kind,path,content_id,seconds,ip,created_at FROM"
                + " user_activity UNION ALL SELECT"
                + " a.id,a.actor_id,ua.platform,'ACTION',a.resource_type || ' / ' ||"
                + " a.action,NULL::uuid,0,'未记录',a.created_at FROM audit_event a JOIN user_account"
                + " ua ON ua.id=a.actor_id) e JOIN user_account u ON u.id=e.actor_id LEFT JOIN"
                + " knowledge_content c ON c.id=e.content_id WHERE e.created_at>=#{start} AND"
                + " e.created_at<#{end} AND (#{user}::uuid IS NULL OR e.actor_id=#{user}) AND"
                + " (#{kind}::text IS NULL OR e.kind=#{kind})";

    /**
     * 幂等写入浏览片段，每账号每30秒最多一条非零时长，避免多标签并行累计。
     *
     * @param w 输入
     * @param actor 身份
     * @param ip 观测IP
     */
    @Insert(
            "INSERT INTO"
                + " user_activity(id,actor_id,platform,kind,path,content_id,seconds,ip,time_bucket)"
                + " VALUES(#{w.id},#{actor.id},#{actor.platform},'VIEW',#{w.path},#{w.contentId},#{w.seconds},#{ip},floor(extract(epoch"
                + " from now())/30)) ON CONFLICT DO NOTHING")
    void insert(@Param("w") ActivityDtos.Write w, @Param("actor") Actor actor, String ip);

    /**
     * 限制每账号一分钟上报频率。
     *
     * @param actor 用户
     * @return 近期记录数
     */
    @Select(
            "SELECT count(*) FROM user_activity WHERE actor_id=#{actor} AND"
                    + " created_at>now()-interval '1 minute'")
    int recent(UUID actor);

    /**
     * 知识阅读必须仍在授权范围内。
     *
     * @param id 内容
     * @param student 学生
     * @return 是否可读
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM knowledge_content c WHERE c.id=#{id} AND"
                    + " c.state='PUBLISHED' AND c.deleted_at IS NULL AND "
                    + cn.zhixiang.platform.knowledge.mapper.ContentMapper.ACCESS
                    + ")")
    boolean readable(UUID id, UUID student);

    /**
     * 查询活动页。
     *
     * @param start 含起点
     * @param end 排他终点
     * @param user 可选用户
     * @param kind 可选类型
     * @param size 页大小
     * @param offset 偏移
     * @return 实际事件
     */
    @Select(
            "SELECT e.id,e.actor_id,u.display_name,e.platform,e.kind,e.path,e.content_id,c.title AS"
                    + " content_title,e.seconds,e.ip,e.created_at"
                    + FILTER
                    + " ORDER BY e.created_at DESC,e.id DESC LIMIT #{size} OFFSET #{offset}")
    List<ActivityEntity> list(
            OffsetDateTime start, OffsetDateTime end, UUID user, String kind, int size, int offset);

    /**
     * 同条件总数。
     *
     * @param start 起点
     * @param end 终点
     * @param user 用户
     * @param kind 类型
     * @return 总数
     */
    @Select("SELECT count(*)" + FILTER)
    long count(OffsetDateTime start, OffsetDateTime end, UUID user, String kind);

    /**
     * 按北京时间汇总整个过滤范围，不受列表分页影响。
     *
     * @param start 起点
     * @param end 终点
     * @param user 可选用户
     * @param kind 可选类型
     * @return 日统计
     */
    @Select(
            "SELECT (e.created_at AT TIME ZONE 'Asia/Shanghai')::date AS day,count(DISTINCT"
                + " e.actor_id) FILTER(WHERE e.kind='LOGIN') AS login_users,count(*) FILTER(WHERE"
                + " e.kind='LOGIN') AS logins,count(*) FILTER(WHERE e.kind='VIEW' AND e.seconds=0)"
                + " AS views,coalesce(sum(e.seconds),0) AS active_seconds,coalesce(sum(e.seconds)"
                + " FILTER(WHERE e.content_id IS NOT NULL),0) AS learning_seconds"
                    + FILTER
                    + " GROUP BY day ORDER BY day")
    List<ActivityDtos.Daily> daily(
            OffsetDateTime start, OffsetDateTime end, UUID user, String kind);
}
