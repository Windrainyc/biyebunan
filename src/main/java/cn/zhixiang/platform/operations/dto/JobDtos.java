package cn.zhixiang.platform.operations.dto;

import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.exception.BusinessException;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.*;

/** 运维任务响应，不携带对象地址或供应商密钥。 */
public final class JobDtos {
    private JobDtos() {}

    /**
     * 任务视图。
     *
     * @param id 任务ID
     * @param version CAS版本
     * @param createdAt 创建时间
     * @param updatedAt 修改时间
     * @param kind 真实任务类别
     * @param state 调度状态
     * @param progress 已完成百分比；未提供细分进度时为0
     * @param resultAssetId 成功结果资产，可空
     * @param errorCode 稳定错误码，可空
     */
    public record View(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String kind,
            String state,
            long progress,
            UUID resultAssetId,
            String errorCode) {}

    /**
     * 受控恢复依据。
     *
     * @param reason 重试原因
     */
    public record Retry(@NotBlank @Size(max = 1000) String reason) {}

    /**
     * 查询条件。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 类别、ID或错误码
     * @param status 状态
     * @param sort 排序
     */
    public record Query(int page, int size, String q, String status, String sort) {
        /**
         * 校验输入。
         *
         * @param page 页码
         * @param size 数量
         * @param q 搜索
         * @param status 状态
         * @param sort 排序
         */
        public Query {
            var p = new PageQuery(page, size, q, null, sort);
            q = p.q();
            sort = p.sort();
            if (status != null
                    && !Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "DEAD").contains(status))
                throw new BusinessException(400, "INVALID_FILTER", "任务状态不合法");
        }

        /**
         * 可信排序。
         *
         * @return 排序表达式
         */
        public String orderBy() {
            return new PageQuery(page, size, q, null, sort).orderBy();
        }

        /**
         * 偏移。
         *
         * @return 行数
         */
        public int offset() {
            return (page - 1) * size;
        }

        /**
         * LIKE搜索。
         *
         * @return 已转义参数
         */
        public String pattern() {
            return new PageQuery(page, size, q, null, sort).pattern();
        }
    }
}
