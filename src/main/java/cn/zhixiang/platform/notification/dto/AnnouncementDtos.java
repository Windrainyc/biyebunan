package cn.zhixiang.platform.notification.dto;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.*;

/** 管理员公告与提醒协议，发布后不可变。 */
public final class AnnouncementDtos {
    private AnnouncementDtos() {}

    /**
     * 发布输入。
     *
     * @param title 标题，1至200字
     * @param body 纯文本正文，1至10000字
     * @param kind ANNOUNCEMENT公告或REMINDER提醒
     * @param audience ALL全部当前启用用户或SELECTED指定用户
     * @param recipientIds 指定用户ID，最多200个；全部发送时必须为空
     */
    public record Publish(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 10000) String body,
            @NotNull @Pattern(regexp = "ANNOUNCEMENT|REMINDER") String kind,
            @NotNull @Pattern(regexp = "ALL|SELECTED") String audience,
            @NotNull @Size(max = 200) List<@NotNull UUID> recipientIds) {}

    /**
     * 发布详情。
     *
     * @param id 只读标识
     * @param title 标题
     * @param body 纯文本正文
     * @param kind 公告或提醒类型
     * @param audience 发送范围
     * @param createdAt 发布时间
     * @param recipientCount 接收者快照人数；普通用户为空
     * @param deliveredCount 已落入收件箱人数；普通用户为空
     * @param readCount 已读人数；普通用户为空
     */
    public record View(
            UUID id,
            String title,
            String body,
            String kind,
            String audience,
            OffsetDateTime createdAt,
            Long recipientCount,
            Long deliveredCount,
            Long readCount) {}

    /**
     * 接收者选择项。
     *
     * @param id 用户只读ID
     * @param displayName 显示名
     * @param platform 所属平台
     * @param phoneMasked 脱敏手机，便于区分同名用户
     */
    public record Recipient(UUID id, String displayName, String platform, String phoneMasked) {}
}
