package cn.zhixiang.platform.asset.mapper;

import cn.zhixiang.platform.asset.entity.AssetGcEntity;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/** 对象退休任务登记和有界租约；不删除数据库业务历史。 */
@Mapper
public interface AssetGcMapper {
    /** 登记最多一百个可退休上传源对象，24小时宽限兼容完成回调。 */
    @Insert(
            "INSERT INTO asset_object_gc(asset_id,storage_id,object_key,kind) SELECT"
                    + " a.id,a.storage_id,a.upload_key,'UPLOAD' FROM asset a WHERE"
                    + " a.upload_expires_at<now()-interval '1 day' AND (a.state IN"
                    + " ('UPLOADING','READY','REJECTED') OR a.deleted_at IS NOT NULL) AND NOT"
                    + " EXISTS(SELECT 1 FROM asset_object_gc g WHERE g.asset_id=a.id AND"
                    + " g.kind='UPLOAD') ORDER BY a.id LIMIT 100 ON CONFLICT DO NOTHING")
    void registerUploads();

    /** 登记软删除七天以上且有私有副本的资产；执行时再次核对引用。 */
    @Insert(
            "INSERT INTO asset_object_gc(asset_id,storage_id,object_key,kind) SELECT"
                    + " a.id,a.storage_id,a.private_key,'PRIVATE' FROM asset a WHERE"
                    + " a.deleted_at<now()-interval '7 days' AND a.private_key IS NOT NULL AND NOT"
                    + " EXISTS(SELECT 1 FROM asset_object_gc g WHERE g.asset_id=a.id AND"
                    + " g.kind='PRIVATE') ORDER BY a.id LIMIT 100 ON CONFLICT DO NOTHING")
    void registerPrivate();

    /**
     * 领取到期任务或完成后每日复查。
     *
     * @return 有效租约或空
     */
    @Select(
            value =
                    "UPDATE asset_object_gc SET"
                        + " generation=generation+1,attempts=attempts+1,lease_until=clock_timestamp()+interval"
                        + " '5 minutes' WHERE id=(SELECT id FROM asset_object_gc WHERE"
                        + " next_attempt_at<=now() AND (lease_until IS NULL OR"
                        + " lease_until<clock_timestamp()) ORDER BY next_attempt_at,id FOR UPDATE"
                        + " SKIP LOCKED LIMIT 1) RETURNING"
                        + " id,asset_id,storage_id,object_key,kind,attempt_generation,generation",
            affectData = true)
    AssetGcEntity claim();

    /**
     * 再次检查退休条件，调用方持资产锁。
     *
     * @param task 当前任务
     * @return 是否仍可清理
     */
    @Select(
            "SELECT COALESCE(CASE WHEN #{task.kind}='UPLOAD' THEN"
                + " a.upload_expires_at<now()-interval '1 day' AND (a.state IN"
                + " ('UPLOADING','READY','REJECTED') OR a.deleted_at IS NOT NULL) WHEN"
                + " #{task.kind}='MEDIA' THEN (a.deleted_at<now()-interval '7 days' OR NOT"
                + " EXISTS(SELECT 1 FROM video_task v WHERE v.asset_id=a.id AND v.state='SUCCEEDED'"
                + " AND v.output_key=#{task.objectKey})) AND NOT EXISTS(SELECT 1 FROM video_task v"
                + " WHERE v.asset_id=a.id AND v.generation=#{task.attemptGeneration} AND"
                + " v.state='RUNNING' AND v.lease_until>clock_timestamp()) WHEN"
                + " #{task.kind}='ATTEMPT' THEN (a.deleted_at<now()-interval '7 days' OR"
                + " a.private_key IS DISTINCT FROM #{task.objectKey}) AND NOT EXISTS(SELECT 1 FROM"
                + " asset_validation_task t WHERE t.asset_id=a.id AND"
                + " t.generation=#{task.attemptGeneration} AND t.state='RUNNING' AND"
                + " t.lease_until>clock_timestamp()) ELSE a.deleted_at<now()-interval '7 days'"
                + " END,false) FROM asset a WHERE a.id=#{task.assetId}")
    boolean eligible(@Param("task") AssetGcEntity task);

    /**
     * 复制前记录唯一目标键，崩溃后仍能定位孤立副本。
     *
     * @param asset 资产
     * @param store 存储
     * @param key 全新私有键
     * @param generation 验证执行代次
     */
    @Insert(
            "INSERT INTO"
                + " asset_object_gc(asset_id,storage_id,object_key,kind,attempt_generation,next_attempt_at)"
                + " VALUES(#{asset},#{store},#{key},'ATTEMPT',#{generation},now()+interval '1 day')"
                + " ON CONFLICT DO NOTHING")
    void track(UUID asset, UUID store, String key, long generation);

    /**
     * 完成后每日复查；失败十分钟后重试，不标记成功。
     *
     * @param id 任务
     * @param generation 有效代次
     * @param error 安全错误码，空为成功
     * @return 更新数量
     */
    @Update(
            "UPDATE asset_object_gc SET lease_until=NULL,error_code=#{error},completed_at=CASE WHEN"
                    + " CAST(#{error} AS text) IS NULL THEN now() ELSE completed_at"
                    + " END,next_attempt_at=now()+CASE WHEN CAST(#{error} AS text) IS NULL THEN"
                    + " interval '1 day' ELSE interval '10 minutes' END WHERE id=#{id} AND"
                    + " generation=#{generation} AND lease_until>clock_timestamp()")
    int finish(UUID id, long generation, String error);

    /**
     * 上传媒体副本之前持久登记键，便于回收崩溃遗留文件。
     *
     * @param asset 源资产
     * @param store 存储
     * @param key 新键
     * @param generation 处理代次
     */
    @Insert(
            "INSERT INTO"
                + " asset_object_gc(asset_id,storage_id,object_key,kind,attempt_generation,next_attempt_at)"
                + " VALUES(#{asset},#{store},#{key},'MEDIA',#{generation},now()+interval '1 day')"
                + " ON CONFLICT DO NOTHING")
    void trackMedia(UUID asset, UUID store, String key, long generation);

    /**
     * 当前播放副本引用保护。
     *
     * @param task 回收任务
     * @return 是否为成功任务当前副本
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM video_task WHERE asset_id=#{task.assetId} AND"
                    + " state='SUCCEEDED' AND output_key=#{task.objectKey})")
    boolean currentMedia(@Param("task") AssetGcEntity task);
}
