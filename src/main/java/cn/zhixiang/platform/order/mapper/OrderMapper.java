package cn.zhixiang.platform.order.mapper;

import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.order.dto.OrderDtos;
import cn.zhixiang.platform.order.entity.OrderEntity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 订单持久层，列表和总数都使用相同的服务端数据范围。 */
@Mapper
public interface OrderMapper {
    /**
     * 按真实身份过滤后分页。
     *
     * @param q 查询
     * @param actor 服务端身份
     * @return 当前页
     */
    @Select(
            "<script>SELECT"
                + " id,version,created_at,updated_at,order_no,title,student_id,partner_id,amount::text"
                + " AS amount,currency,status,receipt_status,package_code,school_id,major_id,source,note"
                + " FROM software_order WHERE <choose><when test=\"actor.platform =="
                + " 'admin'\">TRUE</when><when test=\"actor.platform =="
                + " 'partner'\">partner_id=#{actor.id}</when><when test=\"actor.platform =="
                + " 'student'\">student_id=#{actor.id}</when><otherwise>FALSE</otherwise></choose>"
                + " AND (title ILIKE #{q.pattern} OR order_no ILIKE #{q.pattern}) <if"
                + " test='q.status != null'>AND status=#{q.status}</if> ORDER BY ${q.orderBy} LIMIT"
                + " #{q.size} OFFSET #{q.offset}</script>")
    List<OrderEntity> list(@Param("q") OrderDtos.Query q, @Param("actor") Actor actor);

    /**
     * 计算范围内总数。
     *
     * @param q 查询
     * @param actor 服务端身份
     * @return 总数
     */
    @Select(
            "<script>SELECT count(*) FROM software_order WHERE <choose><when test=\"actor.platform"
                + " == 'admin'\">TRUE</when><when test=\"actor.platform =="
                + " 'partner'\">partner_id=#{actor.id}</when><when test=\"actor.platform =="
                + " 'student'\">student_id=#{actor.id}</when><otherwise>FALSE</otherwise></choose>"
                + " AND (title ILIKE #{q.pattern} OR order_no ILIKE #{q.pattern}) <if"
                + " test='q.status != null'>AND status=#{q.status}</if></script>")
    long count(@Param("q") OrderDtos.Query q, @Param("actor") Actor actor);

    /**
     * 单条授权查询，越权统一返回不存在。
     *
     * @param id 订单ID
     * @param actor 服务端身份
     * @return 记录或null
     */
    @Select(
            "<script>SELECT"
                + " id,version,created_at,updated_at,order_no,title,student_id,partner_id,amount::text"
                + " AS amount,currency,status,receipt_status,package_code,school_id,major_id,source,note"
                + " FROM software_order WHERE id=#{id} AND <choose><when test=\"actor.platform =="
                + " 'admin'\">TRUE</when><when test=\"actor.platform =="
                + " 'partner'\">partner_id=#{actor.id}</when><when test=\"actor.platform =="
                + " 'student'\">student_id=#{actor.id}</when><otherwise>FALSE</otherwise></choose></script>")
    OrderEntity get(UUID id, @Param("actor") Actor actor);

    /**
     * 写前锁定范围内订单。
     *
     * @param id 订单ID
     * @param actor 服务端身份
     * @return 加锁记录或null
     */
    @Select(
            "<script>SELECT"
                + " id,version,created_at,updated_at,order_no,title,student_id,partner_id,amount::text"
                + " AS amount,currency,status,receipt_status,package_code,school_id,major_id,source,note"
                + " FROM software_order WHERE id=#{id} AND <choose><when test=\"actor.platform =="
                + " 'admin'\">TRUE</when><when test=\"actor.platform =="
                + " 'partner'\">partner_id=#{actor.id}</when><when test=\"actor.platform =="
                + " 'student'\">student_id=#{actor.id}</when><otherwise>FALSE</otherwise></choose>"
                + " FOR UPDATE</script>")
    OrderEntity lock(UUID id, @Param("actor") Actor actor);

    /**
     * 验证候选中介平台和启用状态；不改变被分配者会话。
     *
     * @param id 中介账户
     * @return 是否有效
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM user_account WHERE id=#{id} AND platform='partner' AND"
                    + " status='ENABLED' AND activated_at IS NOT NULL)")
    boolean partner(UUID id);

    /**
     * 创建草稿，手机号只保存摘要和密文。
     *
     * @param id 订单ID
     * @param w 业务数据
     * @param actor 创建者
     * @param digest 手机摘要
     * @param ciphertext 手机密文
     * @param note 中介备注
     * @param source 权威来源
     */
    @Insert(
            "INSERT INTO"
                + " software_order(id,order_no,title,partner_id,student_phone_digest,student_phone_ciphertext,amount,currency,package_code,source,school_id,major_id,note,created_by,updated_by)"
                + " VALUES(#{id},#{w.orderNo},#{w.title},#{w.partnerId},#{digest},#{ciphertext},CAST(#{w.amount}"
                + " AS numeric),#{w.currency},#{w.packageCode},#{source},#{w.schoolId},#{w.majorId},#{note},#{actor},#{actor})")
    void insert(
            UUID id,
            @Param("w") OrderDtos.Write w,
            UUID actor,
            String digest,
            String ciphertext,
            String note,
            String source);

    /**
     * 草稿修改，归属和订单号不允许通过此接口更改。
     *
     * @param id ID
     * @param version CAS版本
     * @param w 新数据
     * @param actor 操作者
     * @param digest 手机摘要
     * @param ciphertext 手机密文
     * @return 修改行数
     */
    @Update(
            "UPDATE software_order SET"
                + " title=#{w.title},student_phone_digest=#{digest},student_phone_ciphertext=#{ciphertext},amount=CAST(#{w.amount}"
                + " AS numeric),currency=#{w.currency},package_code=#{w.packageCode},school_id=#{w.schoolId},major_id=#{w.majorId},version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id} AND version=#{version}")
    int update(
            UUID id,
            long version,
            @Param("w") OrderDtos.Write w,
            UUID actor,
            String digest,
            String ciphertext);

    /**
     * 中介可修改的窄字段集合。
     *
     * @param id ID
     * @param version CAS版本
     * @param w 新数据
     * @param actor 操作者
     * @return 修改行数
     */
    @Update(
            "UPDATE software_order SET"
                + " school_id=#{w.schoolId},major_id=#{w.majorId},note=#{w.note},version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id} AND version=#{version}")
    int partnerUpdate(UUID id, long version, @Param("w") OrderDtos.PartnerUpdate w, UUID actor);

    /**
     * 调整归属，后续查询立即按新范围执行。
     *
     * @param id ID
     * @param version CAS版本
     * @param partnerId 新中介，可空
     * @param actor 操作者
     * @return 修改行数
     */
    @Update(
            "UPDATE software_order SET"
                + " partner_id=#{partnerId},version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id} AND version=#{version}")
    int assign(UUID id, long version, UUID partnerId, UUID actor);

    /**
     * 查订单关联项目阶段，调用者已锁订单，因此项目写不能并发通过。
     *
     * @param orderId 订单
     * @return 阶段或null
     */
    @Select("SELECT stage FROM software_project WHERE order_id=#{orderId}")
    String projectStage(UUID orderId);

    /**
     * CAS更新状态，不改变收款标记。
     *
     * @param id 订单
     * @param version 版本
     * @param state 新状态
     * @param actor 操作者
     * @return 修改行数
     */
    @Update(
            "UPDATE software_order SET"
                + " status=#{state},version=version+1,updated_at=now(),updated_by=#{actor} WHERE"
                + " id=#{id} AND version=#{version}")
    int transition(UUID id, long version, String state, UUID actor);
}
