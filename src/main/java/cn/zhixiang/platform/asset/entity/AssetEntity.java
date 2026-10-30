package cn.zhixiang.platform.asset.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 内部资产数据。
 *
 * @param id ID
 * @param uploadId 上传会话
 * @param ownerType 所属类型
 * @param projectId 项目
 * @param receiptOrderId 凭证订单
 * @param contentId 知识内容
 * @param forumPostId 论坛帖子
 * @param materialCategoryId 素材分类，仅MATERIAL归属使用
 * @param storageId 存储配置
 * @param filename 文件名
 * @param size 字节数
 * @param sha256 摘要
 * @param mimeType 媒体类型
 * @param uploadKey 隔离键
 * @param privateKey 验证后的私有键
 * @param objectVersion 私有对象版本
 * @param state 状态
 * @param uploadExpiresAt 上传授权到期
 * @param deletedAt 删除标记
 * @param version 资源版本
 * @param createdBy 上传者
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record AssetEntity(
        UUID id,
        UUID uploadId,
        String ownerType,
        UUID projectId,
        UUID receiptOrderId,
        UUID contentId,
        UUID forumPostId,
        UUID materialCategoryId,
        UUID storageId,
        String filename,
        long size,
        String sha256,
        String mimeType,
        String uploadKey,
        String privateKey,
        String objectVersion,
        String state,
        OffsetDateTime uploadExpiresAt,
        OffsetDateTime deletedAt,
        long version,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
