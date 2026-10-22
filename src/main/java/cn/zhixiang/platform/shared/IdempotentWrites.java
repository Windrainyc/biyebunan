package cn.zhixiang.platform.shared;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;
/** 用数据库唯一约束串行化相同幂等请求，响应与业务写入在同一个事务提交。 */
@Service
public class IdempotentWrites {
    private final cn.zhixiang.platform.identity.IdentityMapper identity;private final WriteMapper mapper; private final JsonMapper json;
    /** @param mapper 幂等表访问 @param json 应用 JSON 编码器 @param identity 写入前的权威身份检查 */
    public IdempotentWrites(WriteMapper mapper,JsonMapper json,cn.zhixiang.platform.identity.IdentityMapper identity) { this.mapper=mapper;this.json=json;this.identity=identity; }
    /**
     * 执行业务写；请求版本纳入 hash，旧请求重试可返回原响应，换请求体返回 409。
     * @param actor 操作者 @param method HTTP 方法 @param path 服务端规范路径 @param key 幂等键
     * @param input 已校验 DTO 与版本组成的摘要输入 @param action 事务内业务操作
     * @return 保存的 HTTP 状态、响应 JSON 与 ETag
     */
    @Transactional(rollbackFor=Exception.class)
    public ResponseEntity<String> execute(UUID actor,String method,String path,String key,Object input,Supplier<ResponseEntity<?>> action) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{8,128}")) throw new BusinessException(400,"VALIDATION_ERROR","幂等键须为 8 至 128 位安全字符");
        // 与停用/密码更改/刷新共用账户锁，避免过滤器通过后身份失效仍写入。
        var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !(authentication.getPrincipal() instanceof cn.zhixiang.platform.identity.Actor current) || !current.id().equals(actor))
            throw new BusinessException(401,"SESSION_EXPIRED","身份已失效");
        Long version=identity.lockVersion(actor);
        if(version==null || version!=current.authVersion() || identity.activeSession(current.sessionId())!=1)
            throw new BusinessException(401,"SESSION_EXPIRED","身份已失效");
        String hash=hash(json.writeValueAsString(input));
        if(mapper.claim(actor,method,path,key,hash)==0) {
            var old=mapper.find(actor,method,path,key);
            if(!hash.equals(old.requestHash())) throw new BusinessException(409,"IDEMPOTENCY_MISMATCH","同一幂等键不能提交不同内容");
            if(old.responseStatus()==null) throw new BusinessException(409,"REQUEST_IN_PROGRESS","请求正在处理");
            return response(old.responseStatus(),old.responseBody(),old.responseEtag());
        }
        var result=action.get(); String body=result.getBody()==null?null:json.writeValueAsString(result.getBody());
        String etag=result.getHeaders().getETag();
        mapper.complete(actor,method,path,key,result.getStatusCode().value(),body,etag);
        return response(result.getStatusCode().value(),body,etag);
    }
    private static ResponseEntity<String> response(int status,String body,String etag) {
        var builder=ResponseEntity.status(status);
        if(etag!=null) builder.eTag(etag);
        if(body!=null) builder.contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return builder.body(body);
    }
    private static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
