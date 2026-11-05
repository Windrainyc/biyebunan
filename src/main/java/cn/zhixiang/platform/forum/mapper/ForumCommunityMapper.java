package cn.zhixiang.platform.forum.mapper;

import cn.zhixiang.platform.forum.entity.*;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 学生论坛公开聚合和分页；父评论隐藏时其回复亦不计入。 */
@Mapper
public interface ForumCommunityMapper {
    /** 当前可见评论。 */
    String COMMENTS =
            "SELECT c.* FROM forum_comment c WHERE c.deleted_at IS NULL AND c.state='PUBLISHED' AND"
                    + " (c.parent_id IS NULL OR EXISTS(SELECT 1 FROM forum_comment parent WHERE"
                    + " parent.id=c.parent_id AND parent.deleted_at IS NULL AND"
                    + " parent.state='PUBLISHED'))";

    /** 可见回复统计与近七天热度。 */
    String BASE =
            "WITH visible_comments AS ("
                    + COMMENTS
                    + "), counts AS (SELECT post_id,count(*) AS reply_count, count(*) FILTER(WHERE"
                    + " created_at>=now()-interval '7 days')*3 + count(DISTINCT author_id)"
                    + " FILTER(WHERE created_at>=now()-interval '7 days')*5 AS heat FROM"
                    + " visible_comments GROUP BY post_id) ";

    /** 公开帖子约束。 */
    String PUBLIC =
            "p.deleted_at IS NULL AND p.state IN ('PUBLISHED','LOCKED') AND b.status='ENABLED'";

    /** 列表关联及公开作者信息。 */
    String ROWS =
            "SELECT p.id,p.title,p.body,p.board_id,b.name AS"
                + " board_name,p.author_id,coalesce(u.display_name,'社区成员') AS"
                + " author_name,coalesce(u.avatar_index,0) AS"
                + " avatar_index,p.state,p.created_at,coalesce(c.reply_count,0) AS"
                + " reply_count,coalesce(c.heat,0) AS heat FROM forum_post p JOIN forum_board b ON"
                + " b.id=p.board_id LEFT JOIN user_account u ON u.id=p.author_id LEFT JOIN counts c"
                + " ON c.post_id=p.id ";

    /** 分页和计数使用同一范围，自己的待审帖子仅出现在个人列表或普通列表。 */
    String FILTER =
            " WHERE p.deleted_at IS NULL AND b.status='ENABLED' AND (p.state IN"
                + " ('PUBLISHED','LOCKED') OR (p.state='PENDING' AND p.author_id=#{user})) AND"
                + " (p.title ILIKE #{q.pattern} ESCAPE E'\\\\' OR p.body ILIKE #{q.pattern} ESCAPE"
                + " E'\\\\' OR b.name ILIKE #{q.pattern} ESCAPE E'\\\\' OR EXISTS(SELECT 1 FROM"
                + " forum_post_tag pt JOIN knowledge_tag t ON t.id=pt.tag_id AND t.status='ENABLED'"
                + " WHERE pt.post_id=p.id AND t.name ILIKE #{q.pattern} ESCAPE E'\\\\')) <if"
                + " test='board != null'>AND p.board_id=#{board}</if><if test='scope =="
                + " &quot;mine&quot;'>AND p.author_id=#{user}</if><if test='scope =="
                + " &quot;participated&quot;'>AND EXISTS(SELECT 1 FROM visible_comments vc WHERE"
                + " vc.post_id=p.id AND vc.author_id=#{user})</if><if test='mode =="
                + " &quot;unanswered&quot;'>AND coalesce(c.reply_count,0)=0 AND"
                + " p.state='PUBLISHED'</if>";

    /**
     * 查询学生帖子分页。
     *
     * @param q 已验证分页及搜索
     * @param board 板块
     * @param scope 全部、本人或参与范围
     * @param mode 最新、热度或待回复
     * @param user 当前用户
     * @return 当前页
     */
    @Select(
            "<script>"
                    + BASE
                    + ROWS
                    + FILTER
                    + " ORDER BY <if test='mode == &quot;hot&quot;'>coalesce(c.heat,0)"
                    + " DESC,</if>p.created_at DESC,p.id DESC LIMIT #{q.size} OFFSET"
                    + " #{q.offset}</script>")
    List<ForumFeedRow> feed(
            @Param("q") PageQuery q, UUID board, String scope, String mode, UUID user);

    /**
     * 同过滤总数。
     *
     * @param q 分页搜索
     * @param board 板块
     * @param scope 个人范围
     * @param mode 列表模式
     * @param user 用户
     * @return 总数
     */
    @Select(
            "<script>"
                    + BASE
                    + "SELECT count(*) FROM forum_post p JOIN forum_board b ON b.id=p.board_id LEFT"
                    + " JOIN counts c ON c.post_id=p.id"
                    + FILTER
                    + "</script>")
    long count(@Param("q") PageQuery q, UUID board, String scope, String mode, UUID user);

    /**
     * 读取或初始化论坛社区数据。
     *
     * @return 公开热帖前三名，零互动新帖可显示真实零热度
     */
    @Select(
            BASE
                    + ROWS
                    + " WHERE "
                    + PUBLIC
                    + " AND (p.created_at>=now()-interval '7 days' OR coalesce(c.heat,0)>0) ORDER"
                    + " BY coalesce(c.heat,0) DESC,p.created_at DESC,p.id DESC LIMIT 3")
    List<ForumFeedRow> hot();

    /**
     * 读取或初始化论坛社区数据。
     *
     * @return 近七天公开贡献最多的成员
     */
    @Select(
            BASE
                    + " , contributions AS (SELECT p.author_id,p.created_at FROM forum_post p JOIN"
                    + " forum_board b ON b.id=p.board_id WHERE "
                    + PUBLIC
                    + " UNION ALL SELECT vc.author_id,vc.created_at FROM visible_comments vc JOIN"
                    + " forum_post p ON p.id=vc.post_id JOIN forum_board b ON b.id=p.board_id WHERE"
                    + " "
                    + PUBLIC
                    + ") SELECT u.id,u.display_name,u.avatar_index,count(*) AS contributions FROM"
                    + " contributions x JOIN user_account u ON u.id=x.author_id WHERE"
                    + " x.created_at>=now()-interval '7 days' AND u.status='ENABLED' GROUP BY"
                    + " u.id,u.display_name,u.avatar_index ORDER BY contributions DESC,u.id LIMIT"
                    + " 12")
    List<ForumActiveRow> active();

    /**
     * 读取或初始化论坛社区数据。
     *
     * @return 真实标签和板块名称词频，无自动生成或固定填充词
     */
    @Select(
            "WITH words AS (SELECT p.id,b.name FROM forum_post p JOIN forum_board b ON"
                    + " b.id=p.board_id WHERE "
                    + PUBLIC
                    + " AND p.created_at>=now()-interval '7 days' UNION SELECT p.id,t.name FROM"
                    + " forum_post p JOIN forum_board b ON b.id=p.board_id JOIN forum_post_tag pt"
                    + " ON pt.post_id=p.id JOIN knowledge_tag t ON t.id=pt.tag_id AND"
                    + " t.status='ENABLED' WHERE "
                    + PUBLIC
                    + " AND p.created_at>=now()-interval '7 days') SELECT name,count(DISTINCT id)"
                    + " AS count FROM words GROUP BY name ORDER BY count DESC,name LIMIT 12")
    List<ForumKeywordRow> keywords();
}
