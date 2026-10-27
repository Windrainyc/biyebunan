package cn.zhixiang.platform.identity.mapper;

import cn.zhixiang.platform.identity.dto.PartnerStudentResponse;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 中介名下学生最小资料；每个SQL强制使用当前中介订单范围。 */
@Mapper
public interface PartnerStudentMapper {
    /** 非取消订单的已绑定学生范围，不能传学生ID绕过。 */
    String SCOPE =
            "u.platform='student' AND EXISTS(SELECT 1 FROM software_order o WHERE o.student_id=u.id"
                    + " AND o.partner_id=#{partner} AND o.status!='CANCELLED')";

    /** 不含密码、角色、私聊或文件地址的最小投影。 */
    String C =
            "u.id,u.display_name,u.phone_masked,(SELECT s.name FROM school s WHERE"
                + " s.id=u.school_id) AS school_name,(SELECT count(*) FROM software_order o WHERE"
                + " o.student_id=u.id AND o.partner_id=#{partner} AND o.status IN"
                + " ('CONFIRMED','ACTIVE')) AS active_order_count";

    /** 当前过滤范围。 */
    String FILTER =
            " FROM user_account u WHERE "
                    + SCOPE
                    + " AND (u.display_name ILIKE #{q.pattern} ESCAPE E'\\\\' OR u.phone_masked"
                    + " ILIKE #{q.pattern} ESCAPE E'\\\\') <if test='q.status != null'>AND"
                    + " u.status=#{q.status}</if> ";

    /**
     * 查询当前页。
     *
     * @param q 搜索分页
     * @param partner 当前中介
     * @return 最小资料
     */
    @Select(
            "<script>SELECT "
                    + C
                    + FILTER
                    + " ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
    List<PartnerStudentResponse> list(@Param("q") PageQuery q, UUID partner);

    /**
     * 同范围总数。
     *
     * @param q 过滤
     * @param partner 当前中介
     * @return 数量
     */
    @Select("<script>SELECT count(*)" + FILTER + "</script>")
    long count(@Param("q") PageQuery q, UUID partner);

    /**
     * 详情仍检查当前范围。
     *
     * @param id 学生
     * @param partner 当前中介
     * @return 最小资料或null
     */
    @Select("SELECT " + C + " FROM user_account u WHERE u.id=#{id} AND " + SCOPE)
    PartnerStudentResponse get(UUID id, UUID partner);
}
