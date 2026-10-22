package cn.zhixiang.platform.identity;
import java.util.UUID;
/** 权威数据库查询所得的当前身份，禁止从请求 body 或 query 构造。
 * @param id 账户标识 @param platform 服务端账户平台
 */
public record Actor(UUID id,String platform) {}
