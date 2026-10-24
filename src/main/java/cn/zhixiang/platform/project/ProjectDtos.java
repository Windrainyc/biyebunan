package cn.zhixiang.platform.project;
import java.util.UUID;
import java.time.*;
import jakarta.validation.constraints.*;
import cn.zhixiang.platform.shared.*;
/** 项目主体请求与响应，不携带未发布交付物或私聊内容。 */
public final class ProjectDtos {
 private ProjectDtos() {}
 /** 项目维护输入。
 * @param orderId 已确认订单，创建后不可转移
 * @param title 项目题目
 * @param requirements 项目需求，最多20000字
 * @param deliveryDate 交付日期 */
 public record Write(@NotNull UUID orderId,@NotBlank @Size(max=200) String title,@NotNull @Size(max=20000) String requirements,@NotNull LocalDate deliveryDate) {}
 /** 项目响应。
 * @param id 项目ID
 * @param version 并发版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param orderId 订单ID
 * @param title 项目题目
 * @param requirements 需求
 * @param deliveryDate 交付日期
 * @param stage 阶段
 * @param progress 完成里程碑权重之和，0至100 */
 public record View(UUID id,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt,UUID orderId,String title,String requirements,LocalDate deliveryDate,String stage,int progress) {}
 /** 项目查询。
 * @param page 页码
 * @param size 页大小
 * @param q 题目搜索
 * @param status 项目阶段
 * @param sort 白名单排序 */
 public record Query(int page,int size,String q,String status,String sort) {
 /** 验证查询。
 * @param page 页码
 * @param size 页大小
 * @param q 搜索
 * @param status 阶段
 * @param sort 排序 */
 public Query {var base=new PageQuery(page,size,q,null,sort);q=base.q();sort=base.sort();if(status!=null&&!java.util.Set.of("PENDING","REQUIREMENTS","PROPOSAL","DEVELOPMENT","DOCUMENTATION","ACCEPTANCE","DONE","PAUSED").contains(status))throw new BusinessException(400,"INVALID_FILTER","项目阶段不合法");}
 /** SQL排序。
 * @return 白名单表达式 */
 public String orderBy(){return new PageQuery(page,size,q,null,sort).orderBy();}
 /** 页偏移。
 * @return 偏移 */
 public int offset(){return (page-1)*size;}
 /** 字面匹配。
 * @return LIKE参数 */
 public String pattern(){return new PageQuery(page,size,q,null,sort).pattern();}
 }
 /** 中介可见的进度摘要，不含需求正文和交付物链接。
 * @param id 项目ID
 * @param title 题目
 * @param stage 阶段
 * @param progress 进度百分比
 * @param deliveryDate 交付日
 * @param milestones 里程碑摘要，单项目最多100项 */
 public record Progress(UUID id,String title,String stage,int progress,LocalDate deliveryDate,java.util.List<MilestoneDtos.View> milestones) {}
}
