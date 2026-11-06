package cn.zhixiang.platform.configuration.mapper;

import cn.zhixiang.platform.configuration.entity.StorageSecretEntity;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/** OSS密文和检查版本持久化。 */
@Mapper
public interface StorageSecretMapper {
    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 配置
     * @return 内部密文
     */
    @Select(
            "SELECT"
                + " storage_id,version,access_cipher,secret_cipher,access_tail,checked_at,check_error"
                + " FROM storage_secret WHERE storage_id=#{id}")
    StorageSecretEntity get(UUID id);

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 配置
     * @param access AK密文
     * @param secret SK密文
     * @param tail 脱敏尾号
     */
    @Insert(
            "INSERT INTO storage_secret(storage_id,access_cipher,secret_cipher,access_tail)"
                + " VALUES(#{id},#{access},#{secret},#{tail}) ON CONFLICT(storage_id) DO UPDATE SET"
                + " access_cipher=EXCLUDED.access_cipher,secret_cipher=EXCLUDED.secret_cipher,access_tail=EXCLUDED.access_tail,version=storage_secret.version+1,checked_at=NULL,check_error=NULL,updated_at=now()")
    void save(UUID id, String access, String secret, String tail);

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 配置
     * @param version 检查快照版本
     * @param error 安全错误
     * @return 是否记录
     */
    @Update(
            "UPDATE storage_secret SET checked_at=now(),check_error=#{error} WHERE storage_id=#{id}"
                    + " AND version=#{version}")
    int checked(UUID id, long version, String error);

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @return 已配置OSS标识
     */
    @Select("SELECT storage_id FROM storage_secret ORDER BY updated_at DESC")
    java.util.List<UUID> ids();

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @return 默认上传配置，可空
     */
    @Select("SELECT storage_id FROM storage_default WHERE singleton")
    UUID defaultStorage();

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 已检查配置
     */
    @Update("UPDATE storage_default SET storage_id=#{id},version=version+1 WHERE singleton")
    void setDefault(UUID id);
}
