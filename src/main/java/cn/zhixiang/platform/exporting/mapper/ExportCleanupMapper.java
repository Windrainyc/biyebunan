package cn.zhixiang.platform.exporting.mapper;

import cn.zhixiang.platform.exporting.entity.ExportCleanupCandidateEntity;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/** 失效尝试和过期报告的限定对象回收，不扫描或删除其他桶对象。 */
@Mapper
public interface ExportCleanupMapper {
    /**
     * 领取已结束超过一小时的失效尝试或已过期成功报告。
     *
     * @return 待清理对象或null
     */
    @Select(
            value =
                    """
WITH candidate AS (
SELECT a.job_id,a.generation FROM export_attempt a JOIN export_job j ON j.id=a.job_id
WHERE (a.cleaned_at IS NULL OR a.cleaned_at<clock_timestamp()-interval '1 day') AND (a.cleanup_until IS NULL OR a.cleanup_until<clock_timestamp()) AND a.created_at<clock_timestamp()-interval '1 hour'
AND (a.generation<j.generation OR j.state='FAILED' OR (j.state='SUCCEEDED' AND j.result_expires_at<clock_timestamp()))
ORDER BY a.created_at,a.job_id,a.generation FOR UPDATE OF a SKIP LOCKED LIMIT 1
) UPDATE export_attempt a SET cleanup_until=clock_timestamp()+interval '5 minutes' FROM candidate c,export_job j
WHERE a.job_id=c.job_id AND a.generation=c.generation AND j.id=a.job_id
RETURNING a.job_id,a.generation,a.object_key,a.object_version,j.storage_id
""",
            affectData = true)
    ExportCleanupCandidateEntity claim();

    /**
     * 标记清理成功；删除对象重复执行也是安全的。
     *
     * @param id 任务
     * @param generation 尝试代次
     */
    @Update(
            "UPDATE export_attempt SET cleaned_at=clock_timestamp(),cleanup_until=NULL WHERE"
                    + " job_id=#{id} AND generation=#{generation}")
    void finish(UUID id, long generation);
}
