package cn.zhixiang.platform.exporting;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.json.JsonMapper;
class CsvReportTest {
 @Test void quotesNewlinesAndNeutralizesFormulaPrefixes(){var json=new JsonMapper();var csv=new CsvReport(json);String cells=json.writeValueAsString(List.of("=1+1","  @SUM(A1)","\tformula","普通\"中文\n换行","-123","+1"));String output=new String(csv.render("ORDERS",List.of(new ExportDataMapper.Data(null,cells))),StandardCharsets.UTF_8);assertTrue(output.startsWith("\ufeff"));assertTrue(output.contains("\"'=1+1\""));assertTrue(output.contains("\"'  @SUM(A1)\""));assertTrue(output.contains("\"'\tformula\""));assertTrue(output.contains("\"普通\"\"中文\n换行\""));assertTrue(output.contains("\"'-123\""));assertThrows(cn.zhixiang.platform.shared.BusinessException.class,()->csv.render("ORDERS",Collections.nCopies(10001,new ExportDataMapper.Data(null,"[]"))));}
}
