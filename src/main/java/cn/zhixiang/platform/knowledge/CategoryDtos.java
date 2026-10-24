package cn.zhixiang.platform.knowledge;
import jakarta.validation.constraints.*;
import java.util.UUID;
import java.time.OffsetDateTime;
/** 知识分类模型，不包含内容正文或发布状态。 */
public final class CategoryDtos {
 private CategoryDtos() {}
 /** 已验证业务写入请求，字段不含服务端生成的标识或版本。
 * @param name 名称
  * @param code 唯一代码
  * @param parentId 父级，根为null
  * @param sortOrder 非负64位顺序
  * @param status ENABLED或DISABLED */
 public record Write(@NotBlank @Size(max=200) String name,@NotBlank @Size(max=200) String code,UUID parentId,@NotNull @Min(0) Long sortOrder,@NotNull @Pattern(regexp="ENABLED|DISABLED") String status) {}
 /** 只读响应数据，版本及审计字段由服务端生成。
 * @param id 标识
  * @param version CAS
  * @param createdAt 创建
  * @param updatedAt 更新
  * @param name 名称
  * @param code 唯一代码
  * @param parentId 父级
  * @param sortOrder 顺序
  * @param status 状态 */
 public record View(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,String name,String code,UUID parentId,long sortOrder,String status) {}
}
