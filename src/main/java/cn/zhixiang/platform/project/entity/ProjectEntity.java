package cn.zhixiang.platform.project.entity;

import java.time.*;
import java.util.*;

/**
 * 内部项目投影。
 *
 * @param id ID
 * @param version 版本
 * @param createdAt 创建
 * @param updatedAt 修改
 * @param orderId 订单
 * @param title 题目
 * @param requirements 需求
 * @param deliveryDate 日期
 * @param stage 阶段
 * @param progress 进度
 */
public record ProjectEntity(
        UUID id,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID orderId,
        String title,
        String requirements,
        LocalDate deliveryDate,
        String stage,
        int progress) {}
