package cn.zhixiang.platform.forum;
import java.util.*;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.*;
import cn.zhixiang.platform.shared.PageQuery;
/** 评论和举报的持久化端口，写入由帖子锁和数据库约束协调。 */
@Mapper
public interface DiscussionMapper {
 /** 可见评论分页。
  * @param id 帖子
  * @param q 分页
  * @param state 状态过滤
  * @param admin 管理员范围
  * @return 当前页
  */
 @Select("<script>SELECT c.* FROM forum_comment c WHERE c.post_id=#{id} AND c.deleted_at IS NULL <if test='!admin'>AND c.state='PUBLISHED' AND (c.parent_id IS NULL OR EXISTS(SELECT 1 FROM forum_comment p WHERE p.id=c.parent_id AND p.state='PUBLISHED' AND p.deleted_at IS NULL))</if> <if test='state != null'>AND c.state=#{state}</if> AND c.body ILIKE #{q.pattern} ESCAPE E'\\\\' ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
 List<CommentRow> comments(UUID id,@Param("q") PageQuery q,String state,boolean admin);
 /** 同范围评论数量。
  * @param id 帖子
  * @param q 分页
  * @param state 状态过滤
  * @param admin 管理员范围
  * @return 总数
  */
 @Select("<script>SELECT count(*) FROM forum_comment c WHERE c.post_id=#{id} AND c.deleted_at IS NULL <if test='!admin'>AND c.state='PUBLISHED' AND (c.parent_id IS NULL OR EXISTS(SELECT 1 FROM forum_comment p WHERE p.id=c.parent_id AND p.state='PUBLISHED' AND p.deleted_at IS NULL))</if> <if test='state != null'>AND c.state=#{state}</if> AND c.body ILIKE #{q.pattern} ESCAPE E'\\\\'</script>")
 long countComments(UUID id,@Param("q") PageQuery q,String state,boolean admin);
 /** 内部读取评论。
  * @param id 评论
  * @return 记录或空
  */
 @Select("SELECT * FROM forum_comment WHERE id=#{id}")
 CommentRow comment(UUID id);
 /** 创建评论，调用方已锁帖子。
  * @param id 新ID
  * @param post 帖子
  * @param user 作者
  * @param w 内容
  */
 @Insert("INSERT INTO forum_comment(id,post_id,author_id,body,parent_id) VALUES(#{id},#{post},#{user},#{w.body},#{w.parentId})")
 void insertComment(UUID id,UUID post,UUID user,@Param("w") CommentDtos.Write w);
 /** 软删除或审核评论。
  * @param id 评论
  * @param state 状态
  * @param deleted 是否软删
  */
 @Update("UPDATE forum_comment SET state=#{state},deleted_at=CASE WHEN #{deleted} THEN now() ELSE deleted_at END,version=version+1,updated_at=now() WHERE id=#{id}")
 void commentState(UUID id,String state,boolean deleted);
 /** 作者一分钟评论频率。
  * @param user 作者
  * @return 次数
  */
 @Select("SELECT count(*) FROM forum_comment WHERE author_id=#{user} AND created_at>now()-interval '1 minute'")
 long recentComments(UUID user);
 /** 相同举报人同帖子查询。
  * @param post 帖子
  * @param user 举报人
  * @return 举报或空
  */
 @Select("SELECT * FROM forum_report WHERE post_id=#{post} AND reporter_id=#{user}")
 ReportRow existing(UUID post,UUID user);
 /** 新增举报，帖子锁串行化同目标写入。
  * @param id 新ID
  * @param post 帖子
  * @param user 举报人
  * @param reason 理由
  */
 @Insert("INSERT INTO forum_report(id,post_id,reporter_id,reason) VALUES(#{id},#{post},#{user},#{reason})")
 void report(UUID id,UUID post,UUID user,String reason);
 /** 内部读取举报。
  * @param id 举报
  * @return 记录或空
  */
 @Select("SELECT * FROM forum_report WHERE id=#{id}")
 ReportRow reportRow(UUID id);
 /** 锁定举报后改变状态。
  * @param id 举报
  * @return 记录或空
  */
 @Select("SELECT * FROM forum_report WHERE id=#{id} FOR UPDATE")
 ReportRow lockReport(UUID id);
 /** 保存处理状态。
  * @param id 举报
  * @param state 结论
  */
 @Update("UPDATE forum_report SET state=#{state},version=version+1,updated_at=now() WHERE id=#{id}")
 void decision(UUID id,String state);
 /** 管理员举报分页。
  * @param q 分页
  * @param state 状态
  * @return 当前页
  */
 @Select("<script>SELECT * FROM forum_report WHERE reason ILIKE #{q.pattern} ESCAPE E'\\\\' <if test='state != null'>AND state=#{state}</if> ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
 List<ReportRow> reports(@Param("q") PageQuery q,String state);
 /** 举报数量。
  * @param q 分页
  * @param state 状态
  * @return 总数
  */
 @Select("<script>SELECT count(*) FROM forum_report WHERE reason ILIKE #{q.pattern} ESCAPE E'\\\\' <if test='state != null'>AND state=#{state}</if></script>")
 long countReports(@Param("q") PageQuery q,String state);
 /** 每分钟举报频率，账户锁由幂等入口持有。
  * @param user 举报人
  * @return 最近一分钟次数 */
 @Select("SELECT count(*) FROM forum_report WHERE reporter_id=#{user} AND created_at>now()-interval '1 minute'")
 long recentReports(UUID user);
 /** 内部持久化行。
  * @param id 标识
  * @param postId 帖子
  * @param authorId 作者
  * @param parentId 父评论
  * @param body 正文
  * @param state 状态
  * @param version 版本
  * @param createdAt 创建
  * @param updatedAt 更新
  * @param deletedAt 软删
  */
 record CommentRow(UUID id,UUID postId,UUID authorId,UUID parentId,String body,String state,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,OffsetDateTime deletedAt){}
 /** 内部持久化行。
  * @param id 标识
  * @param postId 帖子
  * @param reporterId 举报人
  * @param reason 理由
  * @param state 状态
  * @param version 版本
  * @param createdAt 创建
  * @param updatedAt 更新
  */
 record ReportRow(UUID id,UUID postId,UUID reporterId,String reason,String state,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt){}
}
