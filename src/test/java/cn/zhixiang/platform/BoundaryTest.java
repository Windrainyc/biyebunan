package cn.zhixiang.platform;
import cn.zhixiang.platform.shared.*;
import cn.zhixiang.platform.identity.TokenDigester;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** 输入安全边界测试：分页溢出、SQL 排序注入、弱 ETag 与摘要用途混淆。 */
class BoundaryTest {
 @Test void rejectsUnsafePaginationAndSort() {
  assertThrows(BusinessException.class,()->new PageQuery(0,20,null,null,null));
  assertThrows(BusinessException.class,()->new PageQuery(Integer.MAX_VALUE,100,null,null,null));
  assertThrows(BusinessException.class,()->new PageQuery(1,20,null,null,"id;DROP TABLE school"));
  assertThrows(BusinessException.class,()->new PageQuery(1,20,null,"UNKNOWN",null));
 }
 @Test void rejectsWeakOrWildcardVersions() {
  for(String invalid:new String[]{"*","W/\"1\"","0","\"0\"","\"9999999999999999999\""})
   assertThrows(BusinessException.class,()->Versions.parse(invalid));
  assertEquals(2,Versions.parse("\"2\""));
 }
 @Test void smsIsDisabledByDefault() {
  var gateway=new cn.zhixiang.platform.identity.HttpSmsGateway("","",false,new tools.jackson.databind.json.JsonMapper());
  assertThrows(BusinessException.class,gateway::requireConfigured);
 }
 @Test void totpMatchesRfc6238() {
  byte[] key="12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  assertEquals("287082",cn.zhixiang.platform.identity.TotpVerifier.generate(key,59/30));
  assertEquals("081804",cn.zhixiang.platform.identity.TotpVerifier.generate(key,1111111109L/30));
  assertEquals("050471",cn.zhixiang.platform.identity.TotpVerifier.generate(key,1111111111L/30));
 }
 @Test void separatesDigestDomains() {
  var d=new TokenDigester("test-key-only-012345678901234567890123456789");
  assertNotEquals(d.digest("access","same"),d.digest("refresh","same"));
 }
 @org.junit.jupiter.api.Test void secretEncryptionBindsPurposeAndUsesFreshNonce() {
  var cipher=new cn.zhixiang.platform.shared.SecretCipher("isolated-test-master-0123456789-abcdefghij");
  String one=cipher.encrypt("one","private-test-value"),two=cipher.encrypt("one","private-test-value");
  org.junit.jupiter.api.Assertions.assertNotEquals(one,two);
  org.junit.jupiter.api.Assertions.assertEquals("private-test-value",cipher.decrypt("one",one));
  org.junit.jupiter.api.Assertions.assertThrows(cn.zhixiang.platform.shared.BusinessException.class,()->cipher.decrypt("two",one));
  org.junit.jupiter.api.Assertions.assertThrows(cn.zhixiang.platform.shared.BusinessException.class,()->cipher.decrypt("one",one.substring(0,one.length()-4)+"AAAA"));
 }

}
