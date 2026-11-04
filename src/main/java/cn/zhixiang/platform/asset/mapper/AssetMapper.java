package cn.zhixiang.platform.asset.mapper;

import cn.zhixiang.platform.asset.dto.AssetDtos;
import cn.zhixiang.platform.asset.entity.AssetEntity;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 资产访问端口，外部对象存储操作由事务外的worker执行。 */
@Mapper
public interface AssetMapper {
    /** 内部完整投影字段，不直接返回HTTP。 */
    String COLS =
            "id,upload_id,owner_type,project_id,receipt_order_id,content_id,forum_post_id,material_category_id,profile_user_id,storage_id,filename,size,sha256,mime_type,upload_key,private_key,object_version,state,upload_expires_at,deleted_at,version,created_by,created_at,updated_at";

    /** 素材库过滤；只有显式归属素材库的资产才可被管理端复用。 */
    String MATERIAL_FILTER =
            " FROM asset WHERE owner_type='MATERIAL' AND deleted_at IS NULL AND filename ILIKE"
                + " #{q.pattern} <if test='q.categoryId != null'>AND"
                + " material_category_id=#{q.categoryId}</if> <if test='q.state != null'>AND"
                + " state=#{q.state}</if> <if test='q.kind != null'>AND (CASE WHEN mime_type LIKE"
                + " 'image/%' THEN 'IMAGE' WHEN mime_type LIKE 'video/%' THEN 'VIDEO' WHEN"
                + " mime_type LIKE 'text/%' OR mime_type IN"
                + " ('application/pdf','application/msword','application/vnd.openxmlformats-officedocument.wordprocessingml.document','application/vnd.ms-excel','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','application/vnd.ms-powerpoint','application/vnd.openxmlformats-officedocument.presentationml.presentation')"
                + " THEN 'DOCUMENT' ELSE 'OTHER' END)=#{q.kind}</if>";

    /**
     * 查询共享素材，禁止混入项目凭证或论坛文件。
     *
     * @param q 过滤条件
     * @return 当前页资产
     */
    @Select(
            "<script>SELECT "
                    + COLS
                    + MATERIAL_FILTER
                    + " ORDER BY ${q.orderBy} LIMIT #{q.size} OFFSET #{q.offset}</script>")
    java.util.List<AssetEntity> materials(@Param("q") AssetDtos.MaterialQuery q);

    /**
     * 相同权限范围的素材数量。
     *
     * @param q 条件
     * @return 数量
     */
    @Select("<script>SELECT count(*)" + MATERIAL_FILTER + "</script>")
    long materialCount(@Param("q") AssetDtos.MaterialQuery q);

    /**
     * 查询资产。
     *
     * @param id ID
     * @return 行或null
     */
    @Select("SELECT " + COLS + " FROM asset WHERE id=#{id}")
    AssetEntity get(UUID id);

    /**
     * 查上传对应资产。
     *
     * @param id 上传ID
     * @return 行或null
     */
    @Select("SELECT " + COLS + " FROM asset WHERE upload_id=#{id}")
    AssetEntity upload(UUID id);

    /**
     * 锁资产。
     *
     * @param id ID
     * @return 行或null
     */
    @Select("SELECT " + COLS + " FROM asset WHERE id=#{id} FOR UPDATE")
    AssetEntity lock(UUID id);

    /**
     * 登记隔离上传，不触发外部写。
     *
     * @param id 资产
     * @param uploadId 上传会话
     * @param w 输入
     * @param storageId 固定存储配置
     * @param key 隔离对象键
     * @param expiry 签名到期
     * @param actor 上传者
     */
    @Insert(
            "INSERT INTO"
                + " asset(id,upload_id,owner_type,project_id,receipt_order_id,content_id,forum_post_id,material_category_id,profile_user_id,storage_id,filename,size,sha256,mime_type,upload_key,upload_expires_at,created_by,updated_by)"
                + " VALUES(#{id},#{uploadId},#{w.ownerType},CASE WHEN #{w.ownerType}='PROJECT' THEN"
                + " #{w.ownerId} ELSE NULL END,CASE WHEN #{w.ownerType}='RECEIPT' THEN #{w.ownerId}"
                + " ELSE NULL END,CASE WHEN #{w.ownerType}='KNOWLEDGE' THEN #{w.ownerId} ELSE NULL"
                + " END,CASE WHEN #{w.ownerType}='FORUM' THEN #{w.ownerId} ELSE NULL END,CASE WHEN"
                + " #{w.ownerType}='MATERIAL' THEN #{w.ownerId} ELSE NULL END,CASE WHEN"
                + " #{w.ownerType}='PROFILE' THEN #{w.ownerId} ELSE NULL"
                + " END,#{storageId},#{w.filename},#{w.size},#{w.sha256},#{w.mimeType},#{key},#{expiry},#{actor},#{actor})")
    void insert(
            UUID id,
            UUID uploadId,
            @Param("w") AssetDtos.Upload w,
            UUID storageId,
            String key,
            OffsetDateTime expiry,
            UUID actor);

    /**
     * 转隔离并排队前更新版本。
     *
     * @param id 资产
     * @param actor 操作者
     */
    @Update(
            "UPDATE asset SET"
                    + " state='QUARANTINED',version=version+1,updated_at=now(),updated_by=#{actor}"
                    + " WHERE id=#{id} AND state='UPLOADING'")
    void quarantine(UUID id, UUID actor);

    /**
     * 幂等登记异步任务。
     *
     * @param id 资产
     */
    @Insert("INSERT INTO asset_validation_task(asset_id) VALUES(#{id}) ON CONFLICT DO NOTHING")
    void enqueue(UUID id);

    /**
     * 标记删除保留历史；对象清理由独立保留策略处理。
     *
     * @param id ID
     * @param actor 操作者
     */
    @Update(
            "UPDATE asset SET"
                + " deleted_at=now(),version=version+1,updated_at=now(),updated_by=#{actor} WHERE"
                + " id=#{id}")
    void delete(UUID id, UUID actor);

    /**
     * 验证上传完成请求的最晚期限。
     *
     * @param id ID
     * @return 签名到期一天内
     */
    @Select("SELECT upload_expires_at+interval '1 day'>clock_timestamp() FROM asset WHERE id=#{id}")
    boolean completable(UUID id);

    /**
     * 检查不可变收款凭证引用；调用方必须已锁资产，避免删除与收款竞争。
     *
     * @param id 资产
     * @return 是否被引用
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM user_account WHERE avatar_asset_id=#{id}) OR EXISTS(SELECT"
                + " 1 FROM service_package WHERE cover_asset_id=#{id}) OR EXISTS(SELECT 1 FROM"
                + " project_requirement_asset WHERE asset_id=#{id}) OR EXISTS(SELECT 1 FROM"
                + " forum_post_asset WHERE asset_id=#{id}) OR EXISTS(SELECT 1 FROM order_receipt"
                + " WHERE proof_asset_id=#{id}) OR EXISTS(SELECT 1 FROM project_delivery WHERE"
                + " asset_id=#{id}) OR EXISTS(SELECT 1 FROM knowledge_content WHERE asset_id=#{id}"
                + " OR cover_asset_id=#{id}) OR EXISTS(SELECT 1 FROM knowledge_version WHERE"
                + " asset_id=#{id} OR cover_asset_id=#{id})")
    boolean referenced(UUID id);

    /**
     * 当前学生是否拥有该资产的已发布项目交付。
     *
     * @param id 资产
     * @param student 学生
     * @return 是否允许下载
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM project_requirement_asset r JOIN software_project rp ON"
                    + " rp.id=r.project_id JOIN software_order ro ON ro.id=rp.order_id WHERE"
                    + " r.asset_id=#{id} AND ro.student_id=#{student} AND ro.status IN"
                    + " ('CONFIRMED','ACTIVE','COMPLETED','ARCHIVED')) OR EXISTS(SELECT 1 FROM"
                    + " project_delivery d JOIN software_project p ON p.id=d.project_id JOIN"
                    + " software_order o ON o.id=p.order_id WHERE d.asset_id=#{id} AND"
                    + " d.state='PUBLISHED' AND o.student_id=#{student} AND o.status IN"
                    + " ('CONFIRMED','ACTIVE','COMPLETED','ARCHIVED')) OR EXISTS(SELECT 1 FROM"
                    + " knowledge_content c WHERE (c.asset_id=#{id} OR c.cover_asset_id=#{id}) AND"
                    + " c.state='PUBLISHED' AND c.deleted_at IS NULL AND "
                    + cn.zhixiang.platform.knowledge.mapper.ContentMapper.ACCESS
                    + ")")
    boolean studentVisible(UUID id, UUID student);
}
