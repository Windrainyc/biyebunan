package cn.zhixiang.platform.chat.mapper;

import cn.zhixiang.platform.chat.entity.AvailableModelEntity;
import cn.zhixiang.platform.shared.dto.PageQuery;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** 目录候选查询；过滤启停、有效价格与凭证，外部可用性由服务层判断。 */
@Mapper
public interface AvailableModelMapper {
    /**
     * 查询有有效价格和凭证的模型配置，不加载密钥正文。
     *
     * @param q 搜索与白名单排序；分页在部署配置过滤后执行
     * @return 排序后的内部候选
     */
    @Select(
            "SELECT d.id,d.name,d.endpoint,d.max_output_tokens FROM model_definition d WHERE"
                + " d.status='ENABLED' AND d.provider='OPENAI_COMPATIBLE' AND (d.name ILIKE"
                + " #{q.pattern} ESCAPE E'\\\\' OR d.code ILIKE #{q.pattern} ESCAPE E'\\\\') AND"
                + " EXISTS(SELECT 1 FROM model_price p WHERE p.model_id=d.id AND p.status='ENABLED'"
                + " AND p.effective_from<=clock_timestamp()) AND EXISTS(SELECT 1 FROM model_key k"
                + " WHERE k.model_id=d.id AND k.status='ENABLED') ORDER BY ${q.orderBy}")
    List<AvailableModelEntity> candidates(@Param("q") PageQuery q);
}
