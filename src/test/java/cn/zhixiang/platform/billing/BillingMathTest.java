package cn.zhixiang.platform.billing;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import cn.zhixiang.platform.shared.BusinessException;
import static org.junit.jupiter.api.Assertions.*;
/** 金额精度与非法供应商用量边界，不依赖真实付费调用。 */
class BillingMathTest {
 @Test void roundsOnlyTheCombinedCostAndDoesNotDoubleCountCachedInput(){
  var p=new BigDecimal("0.000001");
  assertEquals(new BigDecimal("0.000001"),BillingMath.cost(1,1,0,p,p,p));
  assertEquals(new BigDecimal("0.000000"),BillingMath.cost(0,0,0,p,p,p));
  assertEquals(new BigDecimal("3.000000"),BillingMath.cost(1000000,1000000,1000000,new BigDecimal("10"),new BigDecimal("2"),BigDecimal.ONE));
  assertThrows(BusinessException.class,()->BillingMath.cost(1,0,2,p,p,p));
  assertThrows(BusinessException.class,()->BillingMath.cost(-1,0,0,p,p,p));
  assertThrows(BusinessException.class,()->BillingMath.cost(1,0,0,p.negate(),p,p));
 }
}
