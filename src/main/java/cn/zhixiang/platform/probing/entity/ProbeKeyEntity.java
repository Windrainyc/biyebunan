package cn.zhixiang.platform.probing.entity;

import java.util.UUID;

/**
 * 凭证快照，不含明文。
 *
 * @param id 密钥
 * @param version 版本
 * @param secretRef 加密引用
 */
public record ProbeKeyEntity(UUID id, long version, UUID secretRef) {}
