package cn.zhixiang.platform.callback.mapper;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/** 同一上传会话回调去重；调用者先锁定对应资产。 */
@Mapper
public interface OssCallbackMapper {
    /**
     * 查询已处理通知摘要。
     *
     * @param upload 上传会话
     * @return 已验签原文摘要或空
     */
    @Select("SELECT body_digest FROM oss_callback_receipt WHERE upload_id=#{upload}")
    String digest(UUID upload);

    /**
     * 登记已验证对象，和隔离状态更新同事务。
     *
     * @param upload 上传会话
     * @param asset 资产
     * @param digest 原文SHA256
     * @param version 对象存储版本，可空
     */
    @Insert(
            "INSERT INTO oss_callback_receipt(upload_id,asset_id,body_digest,object_version)"
                    + " VALUES(#{upload},#{asset},#{digest},#{version})")
    void insert(UUID upload, UUID asset, String digest, String version);
}
