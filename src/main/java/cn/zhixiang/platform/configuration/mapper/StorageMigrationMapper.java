package cn.zhixiang.platform.configuration.mapper;

import cn.zhixiang.platform.configuration.entity.StorageMigrationEntity;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/** 逐文件迁移清单，不删除源对象或业务记录。 */
@Mapper
public interface StorageMigrationMapper {
    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 资产
     * @param generation 代次
     * @param target 目标配置
     * @param key 本次写入对象键
     */
    @Insert(
            "INSERT INTO storage_migration_attempt(asset_id,generation,target_id,object_key)"
                    + " VALUES(#{id},#{generation},#{target},#{key})")
    void attempt(UUID id, long generation, UUID target, String key);

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param target 目标配置
     * @return 新排队数量
     */
    @Insert(
            "INSERT INTO"
                + " storage_migration(asset_id,source_id,target_id,source_key,source_version,source_asset_version)"
                + " SELECT a.id,a.storage_id,#{target},a.private_key,a.object_version,a.version"
                + " FROM asset a JOIN storage_config s ON s.id=a.storage_id WHERE"
                + " s.provider='S3_COMPATIBLE' AND a.state='READY' AND a.deleted_at IS NULL AND NOT"
                + " EXISTS(SELECT 1 FROM video_task v WHERE v.asset_id=a.id AND (v.output_key IS"
                + " NOT NULL OR v.state IN ('QUEUED','RUNNING'))) ON CONFLICT(asset_id) DO NOTHING")
    int enqueue(UUID target);

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @return 锁定一个可领取任务
     */
    @Select(
            "SELECT"
                + " m.asset_id,m.source_id,m.target_id,m.source_key,m.source_version,m.source_asset_version,m.generation,a.size,a.sha256,a.mime_type"
                + " FROM storage_migration m JOIN asset a ON a.id=m.asset_id WHERE m.state='QUEUED'"
                + " OR (m.state='RUNNING' AND m.lease_until<now()) ORDER BY m.created_at LIMIT 1"
                + " FOR UPDATE OF m SKIP LOCKED")
    StorageMigrationEntity next();

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 资产
     * @return 新代次
     */
    @Select(
            "UPDATE storage_migration SET"
                    + " state='RUNNING',generation=generation+1,lease_until=now()+interval '30"
                    + " minutes',error_code=NULL,updated_at=now() WHERE asset_id=#{id} RETURNING"
                    + " generation")
    long claim(UUID id);

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 资产
     * @param generation 代次
     * @return 是否持有有效租约
     */
    @Select(
            "SELECT 1 FROM storage_migration WHERE asset_id=#{id} AND generation=#{generation} AND"
                    + " state='RUNNING' AND lease_until>now() FOR UPDATE")
    Integer held(UUID id, long generation);

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param task 固定任务
     * @param key 新对象键
     * @param version 新对象版本
     * @return CAS命中数
     */
    @Update(
            "UPDATE asset SET"
                + " storage_id=#{task.targetId},private_key=#{key},object_version=#{version},version=version+1,updated_at=now()"
                + " WHERE id=#{task.assetId} AND version=#{task.sourceAssetVersion} AND"
                + " storage_id=#{task.sourceId} AND private_key=#{task.sourceKey} AND state='READY'"
                + " AND deleted_at IS NULL AND NOT EXISTS(SELECT 1 FROM video_task v WHERE"
                + " v.asset_id=asset.id AND (v.output_key IS NOT NULL OR v.state IN"
                + " ('QUEUED','RUNNING')))")
    int switchAsset(@Param("task") StorageMigrationEntity task, String key, String version);

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param id 资产
     * @param generation 代次
     * @param key 新键
     * @param version 新版本
     * @param error 安全错误
     */
    @Update(
            "UPDATE storage_migration SET state=CASE WHEN CAST(#{error} AS text) IS NULL THEN"
                + " 'DONE' ELSE 'FAILED'"
                + " END,target_key=#{key},target_version=#{version},error_code=#{error},lease_until=NULL,updated_at=now()"
                + " WHERE asset_id=#{id} AND generation=#{generation} AND state='RUNNING'")
    void finish(UUID id, long generation, String key, String version, String error);

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param state 状态
     * @return 清单数量
     */
    @Select("SELECT count(*) FROM storage_migration WHERE state=#{state}")
    long count(String state);

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @return 仍在旧存储的资产数量，含隔离和软删除，禁止漏算退休条件
     */
    @Select(
            "SELECT count(*) FROM asset a JOIN storage_config s ON s.id=a.storage_id WHERE"
                    + " s.provider='S3_COMPATIBLE'")
    long remainingAssets();

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @return 仍依赖旧存储的导出数量
     */
    @Select(
            "SELECT count(*) FROM export_job e JOIN storage_config s ON s.id=e.storage_id WHERE"
                    + " s.provider='S3_COMPATIBLE'")
    long remainingExports();
}
