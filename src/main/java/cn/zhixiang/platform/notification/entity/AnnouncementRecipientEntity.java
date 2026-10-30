package cn.zhixiang.platform.notification.entity;

import java.util.UUID;

/**
 * 接收者查询行。
 *
 * @param id 用户ID
 * @param displayName 显示名
 * @param platform 平台
 * @param phoneMasked 脱敏手机
 */
public record AnnouncementRecipientEntity(
        UUID id, String displayName, String platform, String phoneMasked) {}
