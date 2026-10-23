package cn.zhixiang.platform.shared;
import java.util.UUID;
import org.apache.ibatis.annotations.*;
/** 事务内审计、事件与幂等记录；不允许业务服务提前单独提交。 */
@Mapper
public interface WriteMapper {
    /** @param id 审计ID
     * @param actor 操作者
     * @param action 动作
     * @param type 资源类型
     * @param target 目标
     * @param reason 业务理由，禁止传秘密 */
    @Insert("INSERT INTO audit_event(id,actor_id,action,resource_type,resource_id,detail) VALUES(#{id},#{actor},#{action},#{type},#{target},jsonb_build_object('reason',CAST(#{reason} AS text)))")
    void auditReason(UUID id,UUID actor,String action,String type,UUID target,String reason);
    /**
 * @param actor 操作者
 * @param method 方法
 * @param path 规范路径
 * @param key 幂等键
 * @param hash 请求摘要
 * @return 新建数量，冲突为零 */
    @Insert("INSERT INTO idempotency_record(actor_id,method,path,key,request_hash) VALUES(#{actor},#{method},#{path},#{key},#{hash}) ON CONFLICT DO NOTHING")
    int claim(UUID actor,String method,String path,String key,String hash);
    /**
 * @param actor 操作者
 * @param method 方法
 * @param path 规范路径
 * @param key 幂等键
 * @return 同键已提交结果 */
    @Select("SELECT request_hash,response_status,response_body,response_etag FROM idempotency_record WHERE actor_id=#{actor} AND method=#{method} AND path=#{path} AND key=#{key} FOR UPDATE")
    SavedResponse find(UUID actor,String method,String path,String key);
    /**
 * @param actor 操作者
 * @param method 方法
 * @param path 规范路径
 * @param key 幂等键
 * @param status 状态码
 * @param body JSON
 * @param etag 版本 */
    @Update("UPDATE idempotency_record SET response_status=#{status},response_body=#{body},response_etag=#{etag} WHERE actor_id=#{actor} AND method=#{method} AND path=#{path} AND key=#{key}")
    void complete(UUID actor,String method,String path,String key,int status,String body,String etag);
    /**
 * @param id 审计 ID
 * @param actor 操作者
 * @param action 动作
 * @param type 资源类型
 * @param target 对象 ID */
    @Insert("INSERT INTO audit_event(id,actor_id,action,resource_type,resource_id) VALUES(#{id},#{actor},#{action},#{type},#{target})")
    void audit(UUID id,UUID actor,String action,String type,UUID target);
    /**
 * @param id 事件 ID
 * @param target 聚合 ID
 * @param type 事件类型
 * @param payload 安全 JSON 内容 */
    @Insert("INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES(#{id},#{target},#{type},CAST(#{payload} AS jsonb))")
    void event(UUID id,UUID target,String type,String payload);
    /**
 * @param requestHash 原请求摘要
 * @param responseStatus 状态
 * @param responseBody 原响应
 * @param responseEtag 原版本 */
    record SavedResponse(String requestHash,Integer responseStatus,String responseBody,String responseEtag) {}
}
