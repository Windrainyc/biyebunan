package cn.zhixiang.platform.forum.dto;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.*;

/** 论坛帖子请求与响应，状态和作者只能由服务端决定。 */
public final class PostDtos {
    private PostDtos() {}

    /**
     * 新增或替换正文。
     *
     * @param draft true保存仅本人可见草稿，缺省发布
     * @param tagNames 最多五个新标签名称，发布时去重建立
     * @param boardId 板块
     * @param title 非空标题
     * @param body Markdown或纯文本，不超过20000字
     * @param tagIds 最多20个启用标签
     * @param assetIds 最多20个属于当前帖子且READY的私有附件；先建帖再上传关联
     */
    public record Write(
            @NotNull UUID boardId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 20000) String body,
            @NotNull @Size(max = 20) List<@NotNull UUID> tagIds,
            @NotNull @Size(max = 20) List<@NotNull UUID> assetIds,
            Boolean draft,
            @Size(max = 5) List<@NotBlank @Size(max = 30) String> tagNames) {
        /**
         * 兼容旧调用，默认直接发布。
         *
         * @param boardId 板块
         * @param title 标题
         * @param body 正文
         * @param tagIds 标签
         * @param assetIds 附件
         */
        public Write(
                UUID boardId, String title, String body, List<UUID> tagIds, List<UUID> assetIds) {
            this(boardId, title, body, tagIds, assetIds, false, List.of());
        }
    }

    /**
     * 审核动作保留审计，处理原因可选。
     *
     * @param action APPROVE/HIDE/LOCK/UNLOCK/PIN/UNPIN
     * @param reason 可选审核依据，不包含秘密
     */
    public record Moderation(
            @NotBlank @Pattern(regexp = "APPROVE|HIDE|LOCK|UNLOCK|PIN|UNPIN") String action,
            @Size(max = 200) String reason) {}

    /**
     * 帖子响应。
     *
     * @param id 标识
     * @param version CAS版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param boardId 板块
     * @param title 标题
     * @param body 原始Markdown，前端安全渲染
     * @param tagIds 标签
     * @param assetIds 附件
     * @param authorId 作者
     * @param state 审核状态
     * @param pinned 是否置顶
     */
    public record View(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            UUID boardId,
            String title,
            String body,
            List<UUID> tagIds,
            List<UUID> assetIds,
            UUID authorId,
            String state,
            boolean pinned) {}
}
