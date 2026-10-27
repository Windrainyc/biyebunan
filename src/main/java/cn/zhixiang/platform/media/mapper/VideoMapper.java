package cn.zhixiang.platform.media.mapper;

import cn.zhixiang.platform.media.entity.VideoTaskEntity;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/** 视频任务领取及代次提交，业务授权仍由原资产执行。 */
@Mapper
public interface VideoMapper {
    /** 为已扫描通过的私有视频登记任务，每次最多100条。 */
    @Insert(
            "INSERT INTO video_task(asset_id) SELECT id FROM asset a WHERE state='READY' AND"
                + " deleted_at IS NULL AND mime_type LIKE 'video/%' AND NOT EXISTS(SELECT 1 FROM"
                + " video_task t WHERE t.asset_id=a.id) ORDER BY id LIMIT 100 ON CONFLICT DO"
                + " NOTHING")
    void seed();

    /**
     * 领取一项，可接续进程崩溃但不重复覆盖输出键；最多三次崩溃重领。
     *
     * @return 租约或空
     */
    @Select(
            value =
                    "UPDATE video_task SET"
                        + " state='RUNNING',version=version+1,generation=generation+1,attempts=attempts+1,lease_until=now()+interval"
                        + " '20 minutes',updated_at=now() WHERE asset_id=(SELECT asset_id FROM"
                        + " video_task WHERE state='QUEUED' OR (state='RUNNING' AND"
                        + " lease_until<now()) ORDER BY created_at,asset_id LIMIT 1 FOR UPDATE SKIP"
                        + " LOCKED) RETURNING"
                        + " asset_id,state,generation,attempts,output_key,object_version",
            affectData = true)
    VideoTaskEntity claim();

    /**
     * 查看处理状态。
     *
     * @param id 已授权资产
     * @return 状态或空
     */
    @Select(
            "SELECT asset_id,state,generation,attempts,output_key,object_version FROM video_task"
                    + " WHERE asset_id=#{id}")
    VideoTaskEntity get(UUID id);

    /**
     * 当前租约提交成功；调用方先锁资产并再次确认READY和未删除。
     *
     * @param task 租约
     * @param key 独立副本键
     * @param version 对象版本
     * @param size 字节数
     * @param duration 秒数
     * @param width 宽像素
     * @param height 高像素
     * @return 提交数量
     */
    @Update(
            "UPDATE video_task SET"
                + " state='SUCCEEDED',version=version+1,output_key=#{key},object_version=#{version},output_size=#{size},duration_seconds=#{duration},width=#{width},height=#{height},lease_until=NULL,error_code=NULL,updated_at=now()"
                + " WHERE asset_id=#{task.assetId} AND generation=#{task.generation} AND"
                + " state='RUNNING' AND lease_until>now()")
    int complete(
            @Param("task") VideoTaskEntity task,
            String key,
            String version,
            long size,
            double duration,
            int width,
            int height);

    /**
     * 明确失败不无限重试，原件不受影响。
     *
     * @param task 租约
     * @param error 安全错误码
     * @return 提交数量
     */
    @Update(
            "UPDATE video_task SET"
                + " state='FAILED',version=version+1,error_code=#{error},lease_until=NULL,updated_at=now()"
                + " WHERE asset_id=#{task.assetId} AND generation=#{task.generation} AND"
                + " state='RUNNING'")
    int fail(@Param("task") VideoTaskEntity task, String error);

    /**
     * 定位源资产以遵循资产在前的加锁顺序。
     *
     * @param id 运维任务ID
     * @return 源资产或空
     */
    @Select("SELECT asset_id FROM video_task WHERE id=#{id}")
    UUID assetForJob(UUID id);

    /**
     * 管理员显式重排失败任务，代次和版本递增；未扫描、已删除和运行中不允许恢复。
     *
     * @param id 任务ID
     * @param version CAS版本
     * @return 更新数量
     */
    @Update(
            "UPDATE video_task t SET"
                + " state='QUEUED',version=version+1,generation=generation+1,attempts=0,lease_until=NULL,error_code=NULL,updated_at=now()"
                + " WHERE id=#{id} AND version=#{version} AND state='FAILED' AND EXISTS(SELECT 1"
                + " FROM asset a WHERE a.id=t.asset_id AND a.state='READY' AND a.deleted_at IS"
                + " NULL)")
    int retry(UUID id, long version);
}
