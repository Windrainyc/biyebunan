package cn.zhixiang.platform.exporting;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import cn.zhixiang.platform.shared.BusinessException;
/** 有界CSV序列化；所有单元格加引号并中和电子表格公式前缀。 */
@Component
public class CsvReport {
 private final JsonMapper json;
 /** 注入JSON解析器。
  * @param json 单元格解码器 */
 public CsvReport(JsonMapper json){this.json=json;}
 /** 构造UTF-8 BOM报告，保证中文软件可读，不允许静默截断。
  * @param kind 白名单类型
  * @param rows 有序数据
  * @return 报告字节
  * @throws BusinessException 超过行数或大小上限 */
 public byte[] render(String kind,List<ExportDataMapper.Data> rows){if(rows.size()>10000)throw new BusinessException(422,"EXPORT_TOO_LARGE","导出超过10000行，请缩小日期范围");String header=switch(kind){case "ORDERS"->"订单ID,订单编号,标题,票面金额,状态,创建时间";case "PROJECTS"->"项目ID,订单编号,标题,阶段,交付日期,创建时间";case "USAGE"->"运行ID,学生ID,账务状态,输入词元,输出词元,原结算金额,人工调整金额,预占创建时间";case "AUDIT"->"审计ID,操作者ID,动作,资源类型,资源ID,理由,创建时间";default->throw new IllegalArgumentException("未知导出类型");};var b=new StringBuilder("\ufeff").append(header).append("\r\n");for(var row:rows){var cells=json.readTree(row.cells());for(int i=0;i<cells.size();i++){if(i>0)b.append(',');b.append(cell(cells.get(i).isNull()?"":cells.get(i).asText()));}b.append("\r\n");if(b.length()>20971520)throw large();}byte[] result=b.toString().getBytes(StandardCharsets.UTF_8);if(result.length>20971520)throw large();return result;}
 private String cell(String value){String stripped=value.stripLeading();if(!stripped.isEmpty()&&"=+-@".indexOf(stripped.charAt(0))>=0||value.startsWith("\t")||value.startsWith("\r")||value.startsWith("\n"))value="'"+value;return "\""+value.replace("\"","\"\"")+"\"";}
 private BusinessException large(){return new BusinessException(422,"EXPORT_TOO_LARGE","导出超过20MiB，请缩小日期范围");}
}
