package cn.zhixiang.platform.asset.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 下载签发计数原子更新，多实例共用数据库额度。 */
@Mapper
public interface DownloadGrantMapper {
    /**
     * 仅在未达到上限时增加一次计数。
     *
     * @param key 账号或经过摘要处理的网络来源
     * @param window UTC 时间窗编号
     * @param limit 每窗口次数
     * @param retention 保留秒数
     * @return 更新后次数，达到上限时为空
     */
    @Select(
            "INSERT INTO download_grant_counter(counter_key,window_start,used,expires_at)"
                + " VALUES(#{key},#{window},1,now()+make_interval(secs=>#{retention})) ON"
                + " CONFLICT(counter_key,window_start) DO UPDATE SET"
                + " used=download_grant_counter.used+1 WHERE download_grant_counter.used<#{limit}"
                + " RETURNING used")
    Long acquire(String key, long window, int limit, int retention);

    /** 每次最多清理一千条过期计数，控制持久化表体积。 */
    @Delete(
            "DELETE FROM download_grant_counter WHERE (counter_key,window_start) IN (SELECT"
                    + " counter_key,window_start FROM download_grant_counter WHERE expires_at<now()"
                    + " LIMIT 1000)")
    void prune();
}
