package cn.zhixiang.platform.billing;
import java.math.*;
import cn.zhixiang.platform.shared.BusinessException;
/** 金额全程十进制，每百万词元报价，单次总金额向上保留六位小数。 */
public final class BillingMath {
 private BillingMath(){}
 /** 核算供应商实际用量，cached是input的子集，不重复计费。
 * @param input 总输入词元
 * @param output 输出词元
 * @param cached 命中缓存输入词元
 * @param inputPrice 普通输入每百万价格
 * @param outputPrice 输出每百万价格
 * @param cachedPrice 缓存输入每百万价格
 * @return CNY六位金额
 * @throws BusinessException 用量或价格非法 */
 public static BigDecimal cost(long input,long output,long cached,BigDecimal inputPrice,BigDecimal outputPrice,BigDecimal cachedPrice){if(input<0||output<0||cached<0||cached>input||inputPrice.signum()<0||outputPrice.signum()<0||cachedPrice.signum()<0)throw new BusinessException(422,"USAGE_INVALID","用量或价格不合法");return inputPrice.multiply(BigDecimal.valueOf(input-cached)).add(cachedPrice.multiply(BigDecimal.valueOf(cached))).add(outputPrice.multiply(BigDecimal.valueOf(output))).divide(BigDecimal.valueOf(1000000),6,RoundingMode.CEILING);}
}
