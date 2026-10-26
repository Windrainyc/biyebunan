package cn.zhixiang.platform.chat;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.PageQuery;
/** 部署配置过滤在分页前完成，避免分页空洞与不可调用模型泄露。 */
class AvailableModelServiceTest {
 @Test void filtersUnavailableBeforePagination(){
  var mapper=mock(AvailableModelMapper.class);var gateway=mock(ModelGateway.class);var first=UUID.randomUUID();var second=UUID.randomUUID();
  var query=new PageQuery(1,1,null,null,null);
  when(mapper.candidates(query)).thenReturn(List.of(new AvailableModelMapper.Row(first,"被拒绝","https://blocked.invalid",100),new AvailableModelMapper.Row(second,"可用","https://allowed.invalid",20000)));
  doThrow(new ModelGateway.Failure("MODEL_DISABLED",true)).when(gateway).check("https://blocked.invalid");
  var page=new AvailableModelService(mapper,gateway).list(query,new Actor(UUID.randomUUID(),"student",1,UUID.randomUUID()));
  assertEquals(1,page.total());assertEquals(second,page.items().getFirst().id());assertEquals(8192,page.items().getFirst().maxOutputTokens());
  verify(gateway,never()).stream(any(),any());
 }
 @Test void disabledGatewayProducesEmptyCatalog(){
  var mapper=mock(AvailableModelMapper.class);var gateway=mock(ModelGateway.class);var query=new PageQuery(1,20,null,null,null);
  when(mapper.candidates(query)).thenReturn(List.of(new AvailableModelMapper.Row(UUID.randomUUID(),"配置模型","https://disabled.invalid",100)));
  doThrow(new ModelGateway.Failure("MODEL_DISABLED",true)).when(gateway).check(anyString());
  var page=new AvailableModelService(mapper,gateway).list(query,new Actor(UUID.randomUUID(),"student",1,UUID.randomUUID()));
  assertEquals(0,page.total());assertTrue(page.items().isEmpty());verify(gateway,never()).stream(any(),any());
 }
}
