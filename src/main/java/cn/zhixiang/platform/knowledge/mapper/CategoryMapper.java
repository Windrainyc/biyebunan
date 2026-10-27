package cn.zhixiang.platform.knowledge.mapper;

import cn.zhixiang.platform.knowledge.dto.CategoryDtos;
import cn.zhixiang.platform.knowledge.entity.CategoryEntity;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 分类存储；树写需互斥行，学生读取仅返回启用字典。 */
@Mapper
public interface CategoryMapper {
    /**
     * 返回全局树写互斥行。
     *
     * @return 全局树写互斥行
     */
    @Select("SELECT id FROM category_tree_mutex WHERE id=1 FOR UPDATE")
    int lockTree();

    /**
     * 返回页。
     *
     * @param q 条件
     * @return 页
     */
    @Select(
            "<script>SELECT * FROM knowledge_category WHERE (name ILIKE #{q.pattern} OR code ILIKE"
                + " #{q.pattern}) <if test='q.status != null'>AND status=#{q.status}</if> ORDER BY"
                + " ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
    List<CategoryEntity> list(@Param("q") PageQuery q);

    /**
     * 返回数量。
     *
     * @param q 条件
     * @return 数量
     */
    @Select(
            "<script>SELECT count(*) FROM knowledge_category WHERE (name ILIKE #{q.pattern} OR code"
                    + " ILIKE #{q.pattern}) <if test='q.status != null'>AND"
                    + " status=#{q.status}</if></script>")
    long count(@Param("q") PageQuery q);

    /**
     * 返回记录。
     *
     * @param id 分类
     * @return 记录
     */
    @Select("SELECT * FROM knowledge_category WHERE id=#{id}")
    CategoryEntity get(UUID id);

    /**
     * 返回启用子项数。
     *
     * @param id 父级
     * @return 启用子项数
     */
    @Select("SELECT count(*) FROM knowledge_category WHERE parent_id=#{id} AND status='ENABLED'")
    long enabledChildren(UUID id);

    /**
     * 在调用方业务事务内插入记录，由数据库约束保证唯一性和引用完整性。
     *
     * @param id 新标识
     * @param w 输入
     * @param actor 操作者
     */
    @Insert(
            "INSERT INTO"
                + " knowledge_category(id,name,code,parent_id,sort_order,status,created_by,updated_by)"
                + " VALUES(#{id},#{w.name},#{w.code},#{w.parentId},#{w.sortOrder},#{w.status},#{actor},#{actor})")
    void insert(UUID id, @Param("w") CategoryDtos.Write w, UUID actor);

    /**
     * 返回变更数。
     *
     * @param id 分类
     * @param version CAS
     * @param w 新值
     * @param actor 操作者
     * @return 变更数
     */
    @Update(
            "UPDATE knowledge_category SET"
                + " name=#{w.name},code=#{w.code},parent_id=#{w.parentId},sort_order=#{w.sortOrder},status=#{w.status},version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id} AND version=#{version}")
    int update(UUID id, long version, @Param("w") CategoryDtos.Write w, UUID actor);

    /**
     * 返回删除数；有子项或内容引用时由外键拒绝。
     *
     * @param id 分类
     * @return 删除数；有子项或内容引用时由外键拒绝
     */
    @Delete("DELETE FROM knowledge_category WHERE id=#{id}")
    int delete(UUID id);
}
