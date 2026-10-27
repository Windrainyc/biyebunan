package cn.zhixiang.platform.exporting;
import java.time.LocalDate;
import jakarta.validation.constraints.*;
/** 导出请求，不接收角色、归属、SQL或字段名单。 */
public final class ExportDtos {
 private ExportDtos(){}
 /** 日期范围导出。
  * @param kind ORDERS/PROJECTS/USAGE/AUDIT；中介仅前两类
  * @param from 包含首日
  * @param to 包含末日 */
 public record Request(@NotNull @Pattern(regexp="ORDERS|PROJECTS|USAGE|AUDIT") String kind,@NotNull LocalDate from,@NotNull LocalDate to){}
}
