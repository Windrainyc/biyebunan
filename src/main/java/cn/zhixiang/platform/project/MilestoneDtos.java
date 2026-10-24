package cn.zhixiang.platform.project;
import java.util.UUID;
import java.time.*;
import jakarta.validation.constraints.*;
/** 里程碑接口数据。 */
public final class MilestoneDtos {
 private MilestoneDtos() {}
 /** 里程碑写入。
 * @param name 名称
 * @param dueDate 截止日期，不晚于项目交付日
 * @param weight 百分比权重，同项目总和不能超过100
 * @param completed 是否完成 */
 public record Write(@NotBlank @Size(max=200) String name,@NotNull LocalDate dueDate,@NotNull @Min(1) @Max(100) Integer weight,@NotNull Boolean completed) {}
 /** 里程碑响应。
 * @param id ID
 * @param version 版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param name 名称
 * @param dueDate 截止日
 * @param weight 权重
 * @param completed 完成状态
 * @param projectId 项目 */
 public record View(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,String name,LocalDate dueDate,int weight,boolean completed,UUID projectId) {}
}
