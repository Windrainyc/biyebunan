package cn.zhixiang.platform.project.dto;

import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.exception.BusinessException;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.*;

/** 不可变交付内容及其发布状态。 */
public final class DeliveryDtos {
    private DeliveryDtos() {}

    /**
     * 新版本内容。
     *
     * @param kind 交付类别
     * @param assetId 本项目扫描通过的资产
     * @param note 版本说明
     */
    public record Write(
            @NotNull
                    @Pattern(
                            regexp =
                                    "TASK|PROPOSAL|THESIS_TEMPLATE|SOURCE_CODE|QA_ATTACHMENT|RECORDING")
                    String kind,
            @NotNull UUID assetId,
            @NotBlank @Size(max = 1000) String note) {}

    /**
     * 一次发布多类交付文件，各类最多一个文件，不覆盖历史版本。
     *
     * @param files 本次更新的资料，未提交的类别保留当前发布版
     * @param note 本次版本说明
     */
    public record Batch(
            @NotEmpty @Size(max = 6) List<@jakarta.validation.Valid Write> files,
            @NotBlank @Size(max = 1000) String note) {}

    /**
     * 发布或撤回依据。
     *
     * @param reason 操作原因
     */
    public record Reason(@NotBlank @Size(max = 1000) String reason) {}

    /**
     * 交付响应。
     *
     * @param id ID
     * @param version 并发版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param kind 类别
     * @param assetId 资产
     * @param note 说明
     * @param projectId 项目
     * @param versionNumber 同项目同类别递增序号
     * @param releaseId 发布批次，历史单文件版本为空
     * @param releaseNumber 整体版本号，历史单文件版本为空
     * @param state 发布状态
     */
    public record View(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String kind,
            UUID assetId,
            String note,
            UUID projectId,
            int versionNumber,
            String state,
            UUID releaseId,
            Integer releaseNumber) {}

    /**
     * 分页过滤。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 说明或类别搜索
     * @param status 发布状态
     * @param sort 白名单排序
     */
    public record Query(int page, int size, String q, String status, String sort) {
        /**
         * 归一化输入。
         *
         * @param page 页码
         * @param size 页大小
         * @param q 搜索
         * @param status 状态
         * @param sort 排序
         */
        public Query {
            var p = new PageQuery(page, size, q, null, sort);
            q = p.q();
            sort = p.sort();
            if (status != null
                    && !Set.of("READY", "PUBLISHED", "SUPERSEDED", "WITHDRAWN").contains(status))
                throw new BusinessException(400, "INVALID_FILTER", "交付状态不合法");
        }

        /**
         * 排序表达式。
         *
         * @return 可信列名
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
         * 搜索表达式。
         *
         * @return 转义LIKE
         */
        public String pattern() {
            return new PageQuery(page, size, q, null, sort).pattern();
        }
    }
}
