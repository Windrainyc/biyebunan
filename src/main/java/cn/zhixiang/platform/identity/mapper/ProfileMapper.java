package cn.zhixiang.platform.identity.mapper;

import cn.zhixiang.platform.identity.dto.ProfileRequest;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/** 本人资料数据访问，不提供权限和认证字段更新SQL。 */
@Mapper
public interface ProfileMapper {
    /**
     * 校验锁定本人已扫描头像。
     *
     * @param id 资产
     * @param user 所属用户
     * @return 可用资产或空
     */
    @Select(
            "SELECT id FROM asset WHERE id=#{id} AND profile_user_id=#{user} AND"
                + " owner_type='PROFILE' AND state='READY' AND deleted_at IS NULL AND mime_type IN"
                + " ('image/png','image/jpeg','image/webp') FOR SHARE")
    UUID avatar(UUID id, UUID user);

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
            "SELECT id FROM major WHERE id=#{id} AND (school_id=#{school} OR school_id IS NULL) AND"
                    + " status='ENABLED' FOR SHARE")
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
                + " display_name=#{w.displayName},school_id=coalesce(#{w.schoolId},school_id),major_id=coalesce(#{w.majorId},major_id),avatar_index=coalesce(#{w.avatarIndex},avatar_index),avatar_asset_id=#{w.avatarAssetId},version=version+1,updated_at=now()"
                + " WHERE id=#{id} AND version=#{version}")
    int update(UUID id, long version, @Param("w") ProfileRequest w);
}
