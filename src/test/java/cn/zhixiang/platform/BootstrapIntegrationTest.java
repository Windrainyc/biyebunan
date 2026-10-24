package cn.zhixiang.platform;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** 在独立schema启动真实非Web bootstrap进程；仅使用虚构测试身份和临时0600文件。 */
@EnabledIfEnvironmentVariable(named="ZX_INTEGRATION",matches="true")
class BootstrapIntegrationTest {
 @TempDir Path directory;
 @Test void initializeRenewAndRejectActivatedAccount() throws Exception {
  String schema="zx_test_bootstrap_"+UUID.randomUUID().toString().replace("-","");
  String source=System.getenv("ZX_DB_URL");String url=source.replaceAll("([?&])currentSchema=[^&]+","$1currentSchema="+schema);if(!url.contains("currentSchema="))url+=(url.contains("?")?"&":"?")+"currentSchema="+schema;
  try(var c=DriverManager.getConnection(source,"postgres",System.getenv("ZX_TEST_ADMIN_PASSWORD"));var s=c.createStatement()){
   s.execute("CREATE SCHEMA "+schema+" AUTHORIZATION zhixiang");
   try {
    Path first=directory.resolve("first.json");run(schema,url,first,false);
    assertTrue(Files.readString(first).contains("#token="));assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(first)));
    try(var rows=s.executeQuery("SELECT status,activated_at,mfa_secret_ref FROM "+schema+".user_account")){assertTrue(rows.next());assertEquals("DISABLED",rows.getString(1));assertNull(rows.getObject(2));assertNull(rows.getObject(3));assertFalse(rows.next());}
    Path second=directory.resolve("renewed.json");run(schema,url,second,true);assertNotEquals(Files.readString(first),Files.readString(second));
    try(var rows=s.executeQuery("SELECT count(*) FROM "+schema+".account_invitation WHERE consumed_at IS NULL")){rows.next();assertEquals(1,rows.getInt(1));}
    s.executeUpdate("UPDATE "+schema+".user_account SET activated_at=now(),status='ENABLED'");
    // 即使操作者知道初始化手机号，也不能借离线重发绕过已登记MFA。
    assertThrows(RuntimeException.class,()->run(schema,source.replaceAll("([?&])currentSchema=[^&]+","$1currentSchema="+schema),directory.resolve("blocked.json"),true));
    try(var rows=s.executeQuery("SELECT count(*) FROM "+schema+".account_invitation")){rows.next();assertEquals(2,rows.getInt(1));}
   }finally{s.execute("DROP SCHEMA "+schema+" CASCADE");}
  }
 }
 private void run(String schema,String url,Path output,boolean renew){
  try(var context=new SpringApplicationBuilder(PlatformApplication.class).web(WebApplicationType.NONE).profiles("bootstrap").run(
   "--spring.datasource.url="+url,"--spring.flyway.enabled=true","--spring.flyway.default-schema="+schema,"--spring.flyway.schemas="+schema,
   "--ZX_BOOTSTRAP_PHONE=13300000001","--ZX_BOOTSTRAP_NAME=隔离初始化测试","--ZX_BOOTSTRAP_OUTPUT="+output,"--ZX_BOOTSTRAP_RENEW="+renew,
   "--spring.main.banner-mode=off","--logging.level.root=ERROR")){
   assertFalse(context.isActive(),"bootstrap完成后应关闭非Web容器");
  }
 }
}
