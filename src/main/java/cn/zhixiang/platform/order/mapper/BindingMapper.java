package cn.zhixiang.platform.order.mapper;

import cn.zhixiang.platform.order.entity.BindingEntity;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 归属挑战持久化，锁顺序账户、订单、挑战。 */
@Mapper
public interface BindingMapper {
    /**
     * 锁定与已验证学生手机号一致且可绑定的订单，避免枚举其他手机号订单。
     *
     * @param number 订单号
     * @param actor 学生标识
     * @param phone 手机摘要
     * @return 可绑定订单ID或null
     */
    @Select(
            "SELECT o.id FROM software_order o JOIN user_account u ON u.id=#{actor} WHERE"
                + " o.order_no=#{number} AND o.student_phone_digest=#{phone} AND"
                + " u.phone_digest=#{phone} AND u.phone_verified_at IS NOT NULL AND"
                + " u.platform='student' AND u.status='ENABLED' AND o.status IN"
                + " ('CONFIRMED','ACTIVE') AND (o.student_id IS NULL OR o.student_id=#{actor}) FOR"
                + " UPDATE OF o")
    UUID eligible(String number, UUID actor, String phone);

    /**
     * 查当前学生的已验证手机号摘要。
     *
     * @param actor 学生ID
     * @return 摘要或null
     */
    @Select(
            "SELECT phone_digest FROM user_account WHERE id=#{actor} AND platform='student' AND"
                    + " status='ENABLED' AND phone_verified_at IS NOT NULL")
    String phone(UUID actor);

    /**
     * 创建待发送挑战。
     *
     * @param id 挑战ID
     * @param orderId 订单
     * @param actor 当前学生
     * @param phone 手机摘要
     * @param code 验证码摘要
     * @return 数据库生成的到期时间
     */
    @Select(
            value =
                    "INSERT INTO"
                        + " order_binding_challenge(id,order_id,actor_id,phone_digest,code_digest,state,expires_at)"
                        + " VALUES(#{id},#{orderId},#{actor},#{phone},#{code},'PENDING',clock_timestamp()+interval"
                        + " '5 minutes') RETURNING expires_at",
            affectData = true)
    OffsetDateTime insert(UUID id, UUID orderId, UUID actor, String phone, String code);

    /**
     * 更新短信投递状态，外部调用不在事务中。
     *
     * @param id 挑战
     * @param state READY或FAILED
     */
    @Update("UPDATE order_binding_challenge SET state=#{state} WHERE id=#{id} AND state='PENDING'")
    void sent(UUID id, String state);

    /**
     * 锁定挑战；到期状态由数据库时钟计算。
     *
     * @param id 挑战ID
     * @return 行或null
     */
    @Select(
            "SELECT"
                + " order_id,actor_id,phone_digest,code_digest,state,attempts,expires_at>clock_timestamp()"
                + " AS alive FROM order_binding_challenge WHERE id=#{id} FOR UPDATE")
    BindingEntity lock(UUID id);

    /**
     * 记录一次错误，不得被业务异常回滚。
     *
     * @param id 挑战
     */
    @Update("UPDATE order_binding_challenge SET attempts=attempts+1 WHERE id=#{id} AND attempts<5")
    void failed(UUID id);

    /**
     * 同业务事务消费挑战。
     *
     * @param id 挑战
     */
    @Update(
            "UPDATE order_binding_challenge SET state='CONSUMED',consumed_at=clock_timestamp()"
                    + " WHERE id=#{id} AND state='READY'")
    void consume(UUID id);

    /**
     * 只允许空归属变为当前学生，版本只增加一次。
     *
     * @param orderId 已锁订单
     * @param actor 学生
     * @return 首次绑定行数
     */
    @Update(
            "UPDATE software_order SET"
                    + " student_id=#{actor},version=version+1,updated_at=now(),updated_by=#{actor}"
                    + " WHERE id=#{orderId} AND student_id IS NULL")
    int bind(UUID orderId, UUID actor);
}
