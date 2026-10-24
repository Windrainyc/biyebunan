package cn.zhixiang.platform.asset;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.*;
/** 资产访问端口，外部对象存储操作由事务外的worker执行。 */
@Mapper
public interface AssetMapper {
 /** 内部完整投影字段，不直接返回HTTP。 */
 String COLS="id,upload_id,owner_type,project_id,receipt_order_id,storage_id,filename,size,sha256,mime_type,upload_key,private_key,object_version,state,upload_expires_at,deleted_at,version,created_by,created_at,updated_at";
 /** 查询资产。
 * @param id ID
 * @return 行或null */
 @Select("SELECT "+COLS+" FROM asset WHERE id=#{id}") Row get(UUID id);
 /** 查上传对应资产。
 * @param id 上传ID
 * @return 行或null */
 @Select("SELECT "+COLS+" FROM asset WHERE upload_id=#{id}") Row upload(UUID id);
 /** 锁资产。
 * @param id ID
 * @return 行或null */
 @Select("SELECT "+COLS+" FROM asset WHERE id=#{id} FOR UPDATE") Row lock(UUID id);
 /** 登记隔离上传，不触发外部写。
 * @param id 资产
 * @param uploadId 上传会话
 * @param w 输入
 * @param storageId 固定存储配置
 * @param key 隔离对象键
 * @param expiry 签名到期
 * @param actor 上传者 */
 @Insert("INSERT INTO asset(id,upload_id,owner_type,project_id,receipt_order_id,storage_id,filename,size,sha256,mime_type,upload_key,upload_expires_at,created_by,updated_by) VALUES(#{id},#{uploadId},#{w.ownerType},CASE WHEN #{w.ownerType}='PROJECT' THEN #{w.ownerId} ELSE NULL END,CASE WHEN #{w.ownerType}='RECEIPT' THEN #{w.ownerId} ELSE NULL END,#{storageId},#{w.filename},#{w.size},#{w.sha256},#{w.mimeType},#{key},#{expiry},#{actor},#{actor})")
 void insert(UUID id,UUID uploadId,@Param("w") AssetDtos.Upload w,UUID storageId,String key,OffsetDateTime expiry,UUID actor);
 /** 转隔离并排队前更新版本。
 * @param id 资产
 * @param actor 操作者 */
 @Update("UPDATE asset SET state='QUARANTINED',version=version+1,updated_at=now(),updated_by=#{actor} WHERE id=#{id} AND state='UPLOADING'") void quarantine(UUID id,UUID actor);
 /** 幂等登记异步任务。
 * @param id 资产 */
 @Insert("INSERT INTO asset_validation_task(asset_id) VALUES(#{id}) ON CONFLICT DO NOTHING") void enqueue(UUID id);
 /** 标记删除保留历史；对象清理由独立保留策略处理。
 * @param id ID
 * @param actor 操作者 */
 @Update("UPDATE asset SET deleted_at=now(),version=version+1,updated_at=now(),updated_by=#{actor} WHERE id=#{id}") void delete(UUID id,UUID actor);
 /** 验证上传完成请求的最晚期限。
 * @param id ID
 * @return 签名到期一天内 */
 @Select("SELECT upload_expires_at+interval '1 day'>clock_timestamp() FROM asset WHERE id=#{id}") boolean completable(UUID id);
 /** 检查不可变收款凭证引用；调用方必须已锁资产，避免删除与收款竞争。
 * @param id 资产
 * @return 是否被引用 */
 @Select("SELECT EXISTS(SELECT 1 FROM order_receipt WHERE proof_asset_id=#{id}) OR EXISTS(SELECT 1 FROM project_delivery WHERE asset_id=#{id})") boolean referenced(UUID id);
 /** 当前学生是否拥有该资产的已发布项目交付。
 * @param id 资产
 * @param student 学生
 * @return 是否允许下载 */
 @Select("SELECT EXISTS(SELECT 1 FROM project_delivery d JOIN software_project p ON p.id=d.project_id JOIN software_order o ON o.id=p.order_id WHERE d.asset_id=#{id} AND d.state='PUBLISHED' AND o.student_id=#{student} AND o.status IN ('CONFIRMED','ACTIVE','COMPLETED','ARCHIVED'))") boolean studentVisible(UUID id,UUID student);
 /** 内部资产数据。
 * @param id ID
 * @param uploadId 上传会话
 * @param ownerType 所属类型
 * @param projectId 项目
 * @param receiptOrderId 凭证订单
 * @param storageId 存储配置
 * @param filename 文件名
 * @param size 字节数
 * @param sha256 摘要
 * @param mimeType 媒体类型
 * @param uploadKey 隔离键
 * @param privateKey 验证后的私有键
 * @param objectVersion 私有对象版本
 * @param state 状态
 * @param uploadExpiresAt 上传授权到期
 * @param deletedAt 删除标记
 * @param version 资源版本
 * @param createdBy 上传者
 * @param createdAt 创建时间
 * @param updatedAt 更新时间 */
 record Row(UUID id,UUID uploadId,String ownerType,UUID projectId,UUID receiptOrderId,UUID storageId,String filename,long size,String sha256,String mimeType,String uploadKey,String privateKey,String objectVersion,String state,OffsetDateTime uploadExpiresAt,OffsetDateTime deletedAt,long version,UUID createdBy,OffsetDateTime createdAt,OffsetDateTime updatedAt) {}
}
