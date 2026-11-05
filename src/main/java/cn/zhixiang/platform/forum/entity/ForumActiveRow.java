package cn.zhixiang.platform.forum.entity;

import java.util.UUID;

/**
 * 近七天公开发帖或回复的活跃成员。
 *
 * @param id 用户标识
 * @param displayName 公开昵称
 * @param avatarIndex 默认头像序号，不公开私有上传文件
 * @param contributions 公开发帖和回复次数
 */
public record ForumActiveRow(UUID id, String displayName, int avatarIndex, long contributions) {}
