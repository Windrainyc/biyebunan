package cn.zhixiang.platform.order.dto;

import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.exception.BusinessException;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 订单输入与响应；手机号仅写入加密存储和摘要，不通过订单响应泄漏。 */
public final class OrderDtos {
    private OrderDtos() {}

    /**
     * 管理员创建或编辑未确认草稿。
     *
     * @param title 订单标题
     * @param orderNo 外部业务订单号，唯一且创建后不可更改
     * @param studentPhone 待绑定学生手机号
     * @param partnerId 所属中介，可空；后续变更走归属接口
     * @param amount 人民币金额字符串，六位小数
     * @param currency 币种 CNY
     * @param packageCode 套餐业务代码
     * @param source 来源，管理员新建仅 MANUAL/IMPORT；修改需保持原值
     * @param schoolId 院校
     * @param majorId 专业，必须属于所选院校
     */
    public record Write(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 200) String orderNo,
            @NotNull @Pattern(regexp = "1[0-9]{10}") String studentPhone,
            UUID partnerId,
            @NotNull @Pattern(regexp = "[0-9]{1,14}(\\.[0-9]{1,6})?") String amount,
            @NotNull @Pattern(regexp = "CNY") String currency,
            @NotBlank @Size(max = 200) String packageCode,
            @NotNull @Pattern(regexp = "MANUAL|IMPORT|PARTNER") String source,
            @NotNull UUID schoolId,
            @NotNull UUID majorId) {
        /**
         * 日志安全摘要。
         *
         * @return 不含手机号的文本
         */
        @Override
        public String toString() {
            return "OrderWrite[redacted]";
        }
    }

    /**
     * 中介下单，不能指定金额、学生账户或归属人。
     *
     * @param title 标题
     * @param studentPhone 学生预留手机号
     * @param schoolId 院校
     * @param majorId 专业
     * @param packageCode 套餐代码
     * @param note 业务备注，不填写密码或密钥
     */
    public record PartnerWrite(
            @NotBlank @Size(max = 200) String title,
            @NotNull @Pattern(regexp = "1[0-9]{10}") String studentPhone,
            @NotNull UUID schoolId,
            @NotNull UUID majorId,
            @NotBlank @Size(max = 200) String packageCode,
            @NotBlank @Size(max = 200) String note) {
        /**
         * 日志安全摘要。
         *
         * @return 不含手机号的文本
         */
        @Override
        public String toString() {
            return "PartnerOrderWrite[redacted]";
        }
    }

    /**
     * 中介只可编辑未确认草稿的专业和备注。
     *
     * @param schoolId 院校
     * @param majorId 专业
     * @param note 备注
     */
    public record PartnerUpdate(
            @NotNull UUID schoolId,
            @NotNull UUID majorId,
            @NotBlank @Size(max = 200) String note) {}

    /**
     * 管理员调整中介归属。
     *
     * @param partnerId 新中介，null表示取消中介归属
     * @param reason 审计原因
     */
    public record Assignment(UUID partnerId, @NotBlank @Size(max = 200) String reason) {}

    /**
     * 无手机号的订单响应。
     *
     * @param id 标识
     * @param version 并发版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param orderNo 业务订单号
     * @param title 标题
     * @param studentId 已验证绑定学生，可空
     * @param partnerId 中介，可空
     * @param amount 人民币金额，草稿不表示收款
     * @param currency CNY
     * @param status 业务状态
     * @param receiptStatus 人工收款确认状态
     * @param schoolId 院校标识
     * @param majorId 专业标识
     * @param source 创建来源，不可修改
     * @param note 订单备注
     * @param packageCode 套餐代码
     */
    public record View(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String orderNo,
            String title,
            UUID studentId,
            UUID partnerId,
            String amount,
            String currency,
            String status,
            String receiptStatus,
            String packageCode,
            UUID schoolId,
            UUID majorId,
            String source,
            String note) {}

    /**
     * 订单分页查询，状态与基础配置启停状态分别校验。
     *
     * @param page 页码
     * @param size 页大小
     * @param q 标题或订单号字面搜索
     * @param status 订单状态过滤
     * @param sort 白名单排序
     */
    public record Query(int page, int size, String q, String status, String sort) {
        /**
         * 校验分页与状态。
         *
         * @param page 页码
         * @param size 页大小
         * @param q 搜索词
         * @param status 状态
         * @param sort 排序
         */
        public Query {
            var base = new PageQuery(page, size, q, null, sort);
            q = base.q();
            sort = base.sort();
            if (status != null
                    && !java.util.Set.of(
                                    "DRAFT",
                                    "CONFIRMED",
                                    "ACTIVE",
                                    "COMPLETED",
                                    "CANCELLED",
                                    "ARCHIVED")
                            .contains(status))
                throw new BusinessException(400, "INVALID_FILTER", "订单状态不合法");
        }

        /**
         * 安全排序。
         *
         * @return 白名单 SQL
         */
        public String orderBy() {
            return new PageQuery(page, size, q, null, sort).orderBy();
        }

        /**
         * 分页偏移。
         *
         * @return 偏移量
         */
        public int offset() {
            return (page - 1) * size;
        }

        /**
         * 字面搜索。
         *
         * @return 转义 LIKE 参数
         */
        public String pattern() {
            return new PageQuery(page, size, q, null, sort).pattern();
        }
    }

    /**
     * 订单状态变更请求。
     *
     * @param action CONFIRM、ACTIVATE、COMPLETE、CANCEL或ARCHIVE
     * @param reason 管理员核实原因
     */
    public record Transition(
            @NotNull @Pattern(regexp = "CONFIRM|ACTIVATE|COMPLETE|CANCEL|ARCHIVE") String action,
            @NotBlank @Size(max = 200) String reason) {}
}
