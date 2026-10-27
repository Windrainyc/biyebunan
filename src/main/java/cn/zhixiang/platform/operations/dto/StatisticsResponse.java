package cn.zhixiang.platform.operations.dto;

import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

/**
 * 统计响应；仅包含有数据日期的指标，空列表表示该范围无记录。
 *
 * @param from 首日
 * @param to 末日
 * @param generatedAt 数据库事务快照生成时间
 * @param lagSeconds 0表示直接查询主库，不使用异步投影
 * @param items 日指标
 */
public record StatisticsResponse(
        LocalDate from,
        LocalDate to,
        OffsetDateTime generatedAt,
        long lagSeconds,
        List<StatisticsItem> items) {}
