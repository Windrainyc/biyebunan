package cn.zhixiang.platform.billing;
import java.util.UUID;
import org.apache.ibatis.annotations.*;
/** 原调用返回证据，追加且不允许覆盖冲突数据。 */
@Mapper
public interface UsageEvidenceMapper {
 /** 读取已有证据。
  * @param run 运行
  * @return 证据或null */
 @Select("SELECT * FROM run_usage_evidence WHERE run_id=#{run}") Evidence get(UUID run);
 /** 幂等插入，冲突时调用方必须比对内容。
  * @param run 运行
  * @param kind USAGE或NO_CHARGE
  * @param input 输入，可空
  * @param output 输出，可空
  * @param cached 缓存，可空
  * @param provider 供应商追踪标识，可空
  * @param owner 原执行器
  * @param generation 代次 */
 @Insert("INSERT INTO run_usage_evidence(run_id,kind,input_tokens,output_tokens,cached_tokens,provider_request_id,lease_owner,generation) VALUES(#{run},#{kind},#{input},#{output},#{cached},#{provider},#{owner},#{generation}) ON CONFLICT(run_id) DO NOTHING") void insert(UUID run,String kind,Long input,Long output,Long cached,String provider,UUID owner,long generation);
 /** 可信调用证据，不包含提示或回答正文。
  * @param runId 运行
  * @param kind 证据类型
  * @param inputTokens 输入词元
  * @param outputTokens 输出词元
  * @param cachedTokens 缓存词元
  * @param providerRequestId 供应商请求标识
  * @param leaseOwner 执行器
  * @param generation 代次 */
 record Evidence(UUID runId,String kind,Long inputTokens,Long outputTokens,Long cachedTokens,String providerRequestId,UUID leaseOwner,long generation){}
}
