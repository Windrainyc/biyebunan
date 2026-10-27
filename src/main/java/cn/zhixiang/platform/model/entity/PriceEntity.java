package cn.zhixiang.platform.model.entity;

import cn.zhixiang.platform.model.dto.ModelDtos;

import org.apache.ibatis.annotations.*;

import java.util.*;

/**
 * 持久化查询记录，仅供服务层映射和业务判断。
 *
 * @param id 标识
 * @param version CAS
 * @param createdAt 创建
 * @param updatedAt 更新
 * @param modelId 模型标识，创建后不可更改
 * @param currency 币种，仅 CNY
 * @param inputPerMillion 每百万输入词元人民币价格，最多六位小数
 * @param outputPerMillion 每百万输出词元人民币价格
 * @param cachedInputPerMillion 每百万缓存命中词元人民币价格
 * @param effectiveFrom 生效时间，新价格必须在未来；生效后金额不可覆盖
 * @param status 是否可供新运行选择
 */
public record PriceEntity(
        UUID id,
        long version,
        java.time.OffsetDateTime createdAt,
        java.time.OffsetDateTime updatedAt,
        UUID modelId,
        String currency,
        String inputPerMillion,
        String outputPerMillion,
        String cachedInputPerMillion,
        java.time.OffsetDateTime effectiveFrom,
        ModelDtos.Status status) {}
