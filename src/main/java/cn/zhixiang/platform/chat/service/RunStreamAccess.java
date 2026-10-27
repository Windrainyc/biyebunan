package cn.zhixiang.platform.chat.service;

import cn.zhixiang.platform.identity.model.Actor;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/** 长连接每批重查认证版本、会话和订单归属，不长期信任打开连接时的身份。 */
@Mapper
public interface RunStreamAccess {
    /**
     * 判断当前流是否仍允许读取。
     *
     * @param run 运行
     * @param actor 服务端认证身份
     * @return 有效且属于本人
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM generation_run r JOIN chat_conversation c ON"
                + " c.id=r.conversation_id JOIN software_project p ON p.id=c.project_id JOIN"
                + " software_order o ON o.id=p.order_id JOIN user_account u ON u.id=r.student_id"
                + " JOIN auth_session s ON s.id=#{actor.sessionId} AND s.user_id=u.id WHERE"
                + " r.id=#{run} AND r.student_id=#{actor.id} AND c.student_id=u.id AND c.deleted_at"
                + " IS NULL AND o.student_id=u.id AND o.status IN"
                + " ('CONFIRMED','ACTIVE','COMPLETED','ARCHIVED') AND u.platform='student' AND"
                + " #{actor.platform}='student' AND u.status='ENABLED' AND"
                + " u.auth_version=#{actor.authVersion} AND s.auth_version=u.auth_version AND"
                + " s.revoked_at IS NULL AND s.expires_at>clock_timestamp())")
    boolean allowed(UUID run, @Param("actor") Actor actor);
}
