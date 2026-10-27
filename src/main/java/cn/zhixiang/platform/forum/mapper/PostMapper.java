package cn.zhixiang.platform.forum.mapper;

import cn.zhixiang.platform.forum.dto.PostDtos;
import cn.zhixiang.platform.forum.entity.PostEntity;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 帖子数据端口；学生目录和计数使用相同可见性谓词，防止总数侧信道。 */
@Mapper
public interface PostMapper {
    /** 列表与计数共用过滤；别名限定所有关联列，排序只来自封闭白名单。 */
    String FILTER =
            " FROM forum_post p WHERE p.deleted_at IS NULL <if test='!admin'>AND EXISTS(SELECT 1"
                + " FROM forum_board b WHERE b.id=p.board_id AND b.status='ENABLED') AND (p.state"
                + " IN ('PUBLISHED','LOCKED') OR (p.state='PENDING' AND p.author_id=#{user}))</if>"
                + " <if test='board != null'>AND p.board_id=#{board}</if> <if test='state !="
                + " null'>AND p.state=#{state}</if> AND (p.title ILIKE #{q.pattern} ESCAPE E'\\\\'"
                + " OR p.body ILIKE #{q.pattern} ESCAPE E'\\\\')";

    /**
     * 当前页。
     *
     * @param q 分页
     * @param state 状态
     * @param board 板块
     * @param admin 是否管理端
     * @param user 当前用户
     * @return 帖子行
     */
    @Select(
            "<script>SELECT p.*"
                    + FILTER
                    + " ORDER BY p.${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
    List<PostEntity> list(
            @Param("q") PageQuery q, String state, UUID board, boolean admin, UUID user);

    /**
     * 同过滤计数。
     *
     * @param q 查询
     * @param state 状态
     * @param board 板块
     * @param admin 管理端
     * @param user 用户
     * @return 总数
     */
    @Select("<script>SELECT count(*)" + FILTER + "</script>")
    long count(@Param("q") PageQuery q, String state, UUID board, boolean admin, UUID user);

    /**
     * 内部读取。
     *
     * @param id 标识
     * @return 帖子或空
     */
    @Select("SELECT * FROM forum_post WHERE id=#{id}")
    PostEntity get(UUID id);

    /**
     * 在板块锁之后锁帖子。
     *
     * @param id 标识
     * @return 行或空
     */
    @Select("SELECT * FROM forum_post WHERE id=#{id} FOR UPDATE")
    PostEntity lock(UUID id);

    /**
     * 插入帖子。
     *
     * @param id 标识
     * @param w 正文
     * @param user 作者
     * @param state 初始状态
     */
    @Insert(
            "INSERT INTO forum_post(id,board_id,author_id,title,body,state)"
                    + " VALUES(#{id},#{w.boardId},#{user},#{w.title},#{w.body},#{state})")
    void insert(UUID id, @Param("w") PostDtos.Write w, UUID user, String state);

    /**
     * 替换已锁帖子并重新应用审核策略。
     *
     * @param id 帖子
     * @param w 新内容
     * @param state 目标状态
     */
    @Update(
            "UPDATE forum_post SET"
                + " board_id=#{w.boardId},title=#{w.title},body=#{w.body},state=#{state},pinned=false,version=version+1,updated_at=now()"
                + " WHERE id=#{id}")
    void update(UUID id, @Param("w") PostDtos.Write w, String state);

    /**
     * 审核变更。
     *
     * @param id 已锁帖子
     * @param state 新状态
     * @param pinned 置顶
     */
    @Update(
            "UPDATE forum_post SET"
                    + " state=#{state},pinned=#{pinned},version=version+1,updated_at=now() WHERE"
                    + " id=#{id}")
    void moderate(UUID id, String state, boolean pinned);

    /**
     * 软删除，不移除审计和引用。
     *
     * @param id 已锁帖子
     */
    @Update(
            "UPDATE forum_post SET deleted_at=now(),version=version+1,updated_at=now() WHERE"
                    + " id=#{id}")
    void delete(UUID id);

    /**
     * 标签列表。
     *
     * @param id 帖子
     * @return 标签ID
     */
    @Select("SELECT tag_id FROM forum_post_tag WHERE post_id=#{id} ORDER BY tag_id")
    List<UUID> tags(UUID id);

    /**
     * 清除当前标签关系，历史帖子正文仍保留。
     *
     * @param id 帖子
     */
    @Delete("DELETE FROM forum_post_tag WHERE post_id=#{id}")
    void clearTags(UUID id);

    /**
     * 添加验证后的标签。
     *
     * @param id 帖子
     * @param tag 标签
     */
    @Insert("INSERT INTO forum_post_tag VALUES(#{id},#{tag})")
    void tag(UUID id, UUID tag);

    /**
     * 最近一分钟发帖数；调用者已持作者账户锁，以实现多节点限频。
     *
     * @param user 作者
     * @return 数量
     */
    @Select(
            "SELECT count(*) FROM forum_post WHERE author_id=#{user} AND created_at>now()-interval"
                    + " '1 minute'")
    long recent(UUID user);

    /**
     * 当前帖子附件。
     *
     * @param id 帖子
     * @return 资产ID
     */
    @Select("SELECT asset_id FROM forum_post_asset WHERE post_id=#{id} ORDER BY asset_id")
    List<UUID> assets(UUID id);

    /**
     * 清除当前附件关系；资产本体保留，旧授权最多等待签名到期。
     *
     * @param id 帖子
     */
    @Delete("DELETE FROM forum_post_asset WHERE post_id=#{id}")
    void clearAssets(UUID id);

    /**
     * 绑定已锁定并验证READY同帖的资产。
     *
     * @param id 帖子
     * @param asset 资产
     */
    @Insert("INSERT INTO forum_post_asset VALUES(#{id},#{asset})")
    void asset(UUID id, UUID asset);

    /**
     * 判断资产是否在当前正文里。
     *
     * @param id 帖子
     * @param asset 资产
     * @return 是否引用
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM forum_post_asset WHERE post_id=#{id} AND"
                    + " asset_id=#{asset})")
    boolean hasAsset(UUID id, UUID asset);
}
