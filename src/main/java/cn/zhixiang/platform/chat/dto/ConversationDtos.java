package cn.zhixiang.platform.chat.dto;

import cn.zhixiang.platform.shared.dto.PageQuery;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 私聊会话输入与响应，不公开底层会话认证信息。 */
public final class ConversationDtos {
    private ConversationDtos() {}

    /**
     * 创建会话。
     *
     * @param projectId 当前学生已绑定且可服务项目
     * @param title 会话标题
     */
    public record Write(UUID projectId, @NotBlank @Size(max = 200) String title) {}

    /**
     * 会话元信息。
     *
     * @param id ID
     * @param version 并发版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param projectId 固定项目
     * @param title 标题
     * @param studentId 服务端学生ID
     */
    public record View(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            UUID projectId,
            String title,
            UUID studentId) {}

    /**
     * 会话筛选。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 标题搜索
     * @param sort 排序
     */
    public record Query(int page, int size, String q, String sort) {
        /**
         * 验证输入。
         *
         * @param page 页码
         * @param size 数量
         * @param q 搜索
         * @param sort 排序
         */
        public Query {
            var base = new PageQuery(page, size, q, null, sort);
            q = base.q();
            sort = base.sort();
        }

        /**
         * 白名单排序。
         *
         * @return SQL表达式
         */
        public String orderBy() {
            return new PageQuery(page, size, q, null, sort).orderBy();
        }

        /**
         * 查询偏移。
         *
         * @return 行数
         */
        public int offset() {
            return (page - 1) * size;
        }

        /**
         * 已转义搜索。
         *
         * @return LIKE参数
         */
        public String pattern() {
            return new PageQuery(page, size, q, null, sort).pattern();
        }
    }
}
