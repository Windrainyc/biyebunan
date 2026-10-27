package cn.zhixiang.platform.chat.entity;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/**
 * 领取结果，不包含正文或供应商密钥。
 *
 * @param id 运行
 * @param generation fencing代次
 */
public record RunLeaseLeaseEntity(UUID id, long generation) {}
