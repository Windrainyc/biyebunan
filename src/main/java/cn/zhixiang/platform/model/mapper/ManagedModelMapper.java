package cn.zhixiang.platform.model.mapper;

import cn.zhixiang.platform.model.entity.ManagedServiceEntity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 官方服务数据库端口。 */
@Mapper
public interface ManagedModelMapper {
    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @return 当前状态
     */
    @Select("SELECT * FROM managed_model_service WHERE provider='deepseek'")
    ManagedServiceEntity get();

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @return 加锁状态
     */
    @Select("SELECT * FROM managed_model_service WHERE provider='deepseek' FOR UPDATE")
    ManagedServiceEntity lock();

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param ref 新密文
     * @param version 版本
     * @return 命中数
     */
    @Update(
            "UPDATE managed_model_service SET"
                + " secret_ref=#{ref},enabled=false,checked_at=NULL,check_error=NULL,available_codes='',version=version+1,updated_at=now()"
                + " WHERE provider='deepseek' AND version=#{version}")
    int save(UUID ref, long version);

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param enabled 开关
     * @param version 版本
     * @return 命中数
     */
    @Update(
            "UPDATE managed_model_service SET enabled=#{enabled},version=version+1,updated_at=now()"
                    + " WHERE provider='deepseek' AND version=#{version}")
    int switchTo(boolean enabled, long version);

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param codes 已核对模型
     * @param error 安全错误码
     * @param version 被检查版本
     * @return 命中数
     */
    @Update(
            "UPDATE managed_model_service SET"
                + " checked_at=now(),check_error=#{error},available_codes=#{codes},enabled=false,version=version+1,updated_at=now()"
                + " WHERE provider='deepseek' AND version=#{version}")
    int checked(String codes, String error, long version);

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @return 已选代码
     */
    @Select(
            "SELECT d.code FROM managed_model_binding b JOIN model_definition d ON d.id=b.model_id"
                    + " WHERE b.provider='deepseek' AND b.selected ORDER BY d.code")
    List<String> selected();

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param code 官方代码
     * @return 模型标识
     */
    @Select(
            "SELECT d.id FROM model_definition d JOIN managed_model_binding b ON b.model_id=d.id"
                    + " WHERE d.code=#{code} AND b.provider='deepseek'")
    UUID managed(String code);

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param code 代码
     * @return 已存在标识
     */
    @Select("SELECT id FROM model_definition WHERE code=#{code}")
    UUID existing(String code);

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param model 模型
     * @param priceVersion 价格版本
     */
    @Insert(
            "INSERT INTO managed_model_binding(model_id,provider,price_version)"
                    + " VALUES(#{model},'deepseek',#{priceVersion})")
    void bind(UUID model, String priceVersion);

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param model 模型
     * @param selected 是否选择
     */
    @Update("UPDATE managed_model_binding SET selected=#{selected} WHERE model_id=#{model}")
    void select(UUID model, boolean selected);

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @param model 模型
     * @return 托管密钥标识
     */
    @Select("SELECT id FROM model_key WHERE model_id=#{model} AND name='官方服务托管密钥'")
    UUID key(UUID model);

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @return 旧 DeepSeek 官方配置的启用密文引用，不修改旧记录
     */
    @Select(
            "SELECT k.secret_ref FROM model_key k JOIN model_definition d ON d.id=k.model_id WHERE"
                + " d.endpoint IN"
                + " ('https://api.deepseek.com','https://api.deepseek.com/chat/completions') AND"
                + " k.status='ENABLED' ORDER BY k.rotated_at DESC,k.id LIMIT 1")
    UUID legacySecret();

    /**
     * 官方服务配置操作，遵循版本与权限约束。
     *
     * @return 共享服务是否允许调用
     */
    @Select("SELECT enabled FROM managed_model_service WHERE provider='deepseek'")
    boolean enabled();

    /**
     * 验证启用时仍存在匹配官方模板的现行价格及启用密钥。
     *
     * @param model 模型标识
     * @param input 输入参考价
     * @param output 输出参考价
     * @param cached 缓存参考价
     * @return 配置是否完整
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM model_price p WHERE p.id=(SELECT id FROM model_price WHERE"
                + " model_id=#{model} AND status='ENABLED' AND effective_from<=now() ORDER BY"
                + " effective_from DESC,id DESC LIMIT 1) AND p.currency='CNY' AND"
                + " p.input_per_million=CAST(#{input} AS numeric) AND"
                + " p.output_per_million=CAST(#{output} AS numeric) AND"
                + " p.cached_input_per_million=CAST(#{cached} AS numeric)) AND EXISTS(SELECT 1 FROM"
                + " model_key WHERE model_id=#{model} AND name='官方服务托管密钥' AND status='ENABLED')")
    boolean ready(UUID model, String input, String output, String cached);
}
