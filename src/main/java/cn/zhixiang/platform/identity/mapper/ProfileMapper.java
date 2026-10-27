package cn.zhixiang.platform.identity.mapper;

import cn.zhixiang.platform.identity.dto.ProfileRequest;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/** 本人资料数据访问，不提供权限和认证字段更新SQL。 */
@Mapper
public interface ProfileMapper {
    /**
     * 锁定启用院校。
     *
     * @param id 院校
     * @return ID或null
     */
    @Select("SELECT id FROM school WHERE id=#{id} AND status='ENABLED' FOR SHARE")
    UUID school(UUID id);

    /**
     * 锁定相匹配的启用专业。
     *
     * @param id 专业
     * @param school 院校
     * @return ID或null
     */
    @Select(
            "SELECT id FROM major WHERE id=#{id} AND school_id=#{school} AND status='ENABLED' FOR"
                    + " SHARE")
    UUID major(UUID id, UUID school);

    /**
     * 仅更新资料字段。
     *
     * @param id 当前账户
     * @param version 预期版本
     * @param w 已校验资料
     * @return CAS命中数
     */
    @Update(
            "UPDATE user_account SET"
                + " display_name=#{w.displayName},school_id=#{w.schoolId},major_id=#{w.majorId},version=version+1,updated_at=now()"
                + " WHERE id=#{id} AND version=#{version}")
    int update(UUID id, long version, @Param("w") ProfileRequest w);
}
