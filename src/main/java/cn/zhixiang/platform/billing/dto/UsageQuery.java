package cn.zhixiang.platform.billing.dto;

import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.exception.BusinessException;

import java.time.*;
import java.util.Set;

/**
 * 用量分页过滤；日期按UTC创建日期，from/to均包含当天。
 *
 * @param page 页码
 * @param size 每页数
 * @param q 运行ID字面检索
 * @param status 预占状态，可空
 * @param sort 白名单排序
 * @param from 开始日期，可空
 * @param to 结束日期，可空
 */
public record UsageQuery(
        int page, int size, String q, String status, String sort, LocalDate from, LocalDate to) {
    /**
     * 校验分页、日期与状态，拒绝非法筛选。
     *
     * @param page 页码
     * @param size 每页数
     * @param q 运行ID
     * @param status 账务状态
     * @param sort 排序
     * @param from 开始日期
     * @param to 结束日期
     */
    public UsageQuery {
        var base = new PageQuery(page, size, q, null, sort);
        q = base.q();
        sort = base.sort();
        if (status != null
                && !Set.of("RESERVED", "RECONCILING", "SETTLED", "RELEASED").contains(status))
            throw new BusinessException(400, "INVALID_FILTER", "用量状态不合法");
        if (from != null && to != null && from.isAfter(to))
            throw new BusinessException(400, "INVALID_FILTER", "开始日期晚于结束日期");
        if (to != null && to.equals(LocalDate.MAX))
            throw new BusinessException(400, "INVALID_FILTER", "结束日期超出范围");
    }

    /**
     * 返回封闭排序表达式。
     *
     * @return 带稳定ID的SQL排序
     */
    public String orderBy() {
        return switch (sort) {
            case "createdAt:asc" -> "h.created_at ASC,h.run_id ASC";
            case "updatedAt:desc" -> "h.updated_at DESC,h.run_id DESC";
            default -> "h.created_at DESC,h.run_id DESC";
        };
    }

    /**
     * 分页偏移。
     *
     * @return 已验证偏移
     */
    public int offset() {
        return (page - 1) * size;
    }

    /**
     * 搜索转义。
     *
     * @return 字面LIKE模式
     */
    public String pattern() {
        return new PageQuery(page, size, q, null, sort).pattern();
    }

    /**
     * UTC日期起点。
     *
     * @return 可空起点
     */
    public OffsetDateTime start() {
        return from == null ? null : from.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /**
     * UTC结束日期下一日起点。
     *
     * @return 可空排他终点
     */
    public OffsetDateTime end() {
        return to == null ? null : to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
