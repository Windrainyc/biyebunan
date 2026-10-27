package cn.zhixiang.platform.chat.entity;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 内部持久事件，交由SSE用例序列化；不能未经授权直接暴露。
 *
 * @param runId 运行
 * @param sequence 单调序号
 * @param type 事件类型
 * @param payload JSON对象
 * @param createdAt 创建
 * @param expiresAt 回放到期
 */
public record RunJournalEventEntity(
        UUID runId,
        long sequence,
        String type,
        String payload,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt) {}
