package cn.zhixiang.platform.foundation.mapper;

import cn.zhixiang.platform.foundation.dto.MajorDtos;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 前台专业目录同时过滤停用院校，不能只看专业自身启停。 */
@Mapper
public interface EducationDictionaryMapper {
    /** 共用筛选，学生和中介都只读取启用字典。 */
    String FILTER =
            " FROM major m WHERE m.status='ENABLED' AND EXISTS(SELECT 1 FROM school s WHERE"
                + " s.id=m.school_id AND s.status='ENABLED') AND (m.name ILIKE #{q.pattern} ESCAPE"
                + " E'\\\\' OR m.code ILIKE #{q.pattern} ESCAPE E'\\\\') <if test='school !="
                + " null'>AND m.school_id=#{school}</if> ";

    /**
     * 查询专业页。
     *
     * @param q 搜索分页
     * @param school 可选院校
     * @return 专业页
     */
    @Select(
            "<script>SELECT"
                + " m.id,m.version,m.created_at,m.updated_at,m.name,m.code,m.school_id,m.level,m.status"
                    + FILTER
                    + " ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
    List<MajorDtos.View> majors(@Param("q") PageQuery q, UUID school);

    /**
     * 查询同范围总数。
     *
     * @param q 过滤
     * @param school 院校
     * @return 数量
     */
    @Select("<script>SELECT count(*)" + FILTER + "</script>")
    long count(@Param("q") PageQuery q, UUID school);
}
