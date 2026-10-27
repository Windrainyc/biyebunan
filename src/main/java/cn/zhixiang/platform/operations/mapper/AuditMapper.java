package cn.zhixiang.platform.operations.mapper;

import cn.zhixiang.platform.operations.dto.AuditResponse;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 审计只读仓储；不返回detail原文，私聊元数据也需要额外授权。 */
@Mapper
public interface AuditMapper {
    /** 安全元数据过滤；值全部绑定，私聊资源按额外权限过滤。 */
    String FILTER =
            " FROM audit_event WHERE (#{chat} OR resource_type NOT IN"
                + " ('conversation','conversation_catalog','generation_run','chat_message')) AND"
                + " (#{q.q}='' OR action ILIKE #{q.pattern} ESCAPE '\\' OR resource_type ILIKE"
                + " #{q.pattern} ESCAPE '\\' OR resource_id::text ILIKE #{q.pattern} ESCAPE '\\')";

    /**
     * 查询脱敏审计元数据。
     *
     * @param q 分页与字面搜索
     * @param chat 是否有私聊审计权限
     * @return 当前页
     */
    @Select(
            "SELECT id,actor_id,action,resource_id::text AS"
                    + " target_id,resource_type,detail->>'reason' AS reason,"
                    + " trace_id,created_at"
                    + FILTER
                    + " ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}")
    List<AuditResponse> list(PageQuery q, boolean chat);

    /**
     * 与列表同快照和同过滤统计。
     *
     * @param q 分页条件
     * @param chat 私聊权限
     * @return 总量
     */
    @Select("SELECT count(*)" + FILTER)
    long count(PageQuery q, boolean chat);
}
