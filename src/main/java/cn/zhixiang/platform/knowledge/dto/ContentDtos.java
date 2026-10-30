package cn.zhixiang.platform.knowledge.dto;

import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.exception.BusinessException;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.*;

/** 知识内容编辑、发布和查询数据。 */
public final class ContentDtos {
    private ContentDtos() {}

    /**
     * 草稿输入，文档/视频允许先建无附件草稿后上传。
     *
     * @param title 标题
     * @param categoryId 启用分类
     * @param tagIds 启用标签，最多20且不得重复
     * @param type 内容类型
     * @param assetId 本内容扫描通过的附件，可空
     * @param markdown Markdown正文，最多20万字符，可空
     * @param audience PUBLIC为已登录学生，MEMBER为有效会员，PROJECT为项目归属
     * @param coverAssetId 扫描通过的图片封面，可空
     * @param projectId 项目范围时必填
     */
    public record Write(
            @NotBlank @Size(max = 200) String title,
            @NotNull UUID categoryId,
            @NotNull @Size(max = 20) List<@NotNull UUID> tagIds,
            @NotNull @Pattern(regexp = "VIDEO|DOCUMENT|MARKDOWN") String type,
            UUID assetId,
            @Size(max = 200000) String markdown,
            @NotNull @Pattern(regexp = "PUBLIC|MEMBER|PROJECT") String audience,
            UUID projectId,
            UUID coverAssetId) {}

    /**
     * 内容响应。
     *
     * @param id ID
     * @param version CAS版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param title 标题
     * @param categoryId 分类
     * @param tagIds 标签
     * @param type 类型
     * @param assetId 附件
     * @param markdown 正文
     * @param audience 范围
     * @param projectId 项目
     * @param state 状态
     * @param publishedVersionId 当前发布快照
     * @param coverAssetId 图片封面，可空
     * @param indexState 索引状态
     */
    public record View(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String title,
            UUID categoryId,
            List<UUID> tagIds,
            String type,
            UUID assetId,
            String markdown,
            String audience,
            UUID projectId,
            String state,
            UUID publishedVersionId,
            String indexState,
            UUID coverAssetId) {}

    /**
     * 操作依据。
     *
     * @param reason 审核发布原因
     */
    public record Reason(@NotBlank @Size(max = 1000) String reason) {}

    /**
     * 授权分页。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 标题搜索
     * @param status 内容状态
     * @param sort 排序
     * @param categoryId 分类过滤
     * @param tagId 标签过滤
     * @param contentType 类型过滤
     */
    public record Query(
            int page,
            int size,
            String q,
            String status,
            String sort,
            UUID categoryId,
            UUID tagId,
            String contentType) {
        /**
         * 校验所有过滤值。
         *
         * @param page 页码
         * @param size 数量
         * @param q 搜索
         * @param status 状态
         * @param sort 排序
         * @param categoryId 分类
         * @param tagId 标签
         * @param contentType 类型
         */
        public Query {
            var p = new PageQuery(page, size, q, null, sort);
            q = p.q();
            sort = p.sort();
            if (status != null
                            && !Set.of("DRAFT", "REVIEW", "PUBLISHED", "WITHDRAWN").contains(status)
                    || contentType != null
                            && !Set.of("VIDEO", "DOCUMENT", "MARKDOWN").contains(contentType))
                throw new BusinessException(400, "INVALID_FILTER", "知识过滤值不合法");
        }

        /**
         * 排序。
         *
         * @return 白名单SQL
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
         * 搜索。
         *
         * @return LIKE参数
         */
        public String pattern() {
            return new PageQuery(page, size, q, null, sort).pattern();
        }
    }
}
