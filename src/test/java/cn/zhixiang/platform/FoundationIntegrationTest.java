package cn.zhixiang.platform;

import cn.zhixiang.platform.identity.TokenDigester;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 PostgreSQL 的 HTTP 纵向测试；每次运行独立 schema，测试结束仅删除自身 schema。 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="ZX_INTEGRATION",matches="true")
class FoundationIntegrationTest {
 static final String SCHEMA="zx_test_"+UUID.randomUUID().toString().replace("-","");
 @Autowired MockMvc mvc;
 @Autowired JdbcTemplate db;
 @Autowired TokenDigester digester;
 @Autowired JsonMapper json;
 private String adminToken,partnerToken;
 private UUID adminId;
 @DynamicPropertySource
 static void properties(DynamicPropertyRegistry r) throws Exception {
  try(var c=connection();var s=c.createStatement()){s.execute("CREATE SCHEMA "+SCHEMA+" AUTHORIZATION zhixiang");}
  String url=System.getenv("ZX_DB_URL").replaceAll("([?&])currentSchema=[^&]+","$1currentSchema="+SCHEMA);
  if(!url.contains("currentSchema="))url+=(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
  String jdbc=url;
  r.add("spring.datasource.url",()->jdbc);
  r.add("spring.flyway.enabled",()->true);
  r.add("spring.flyway.default-schema",()->SCHEMA);
  r.add("spring.flyway.schemas",()->SCHEMA);
 }
 static Connection connection() throws SQLException {return DriverManager.getConnection(System.getenv("ZX_DB_URL"),"postgres",System.getenv("ZX_TEST_ADMIN_PASSWORD"));}
 @AfterAll static void cleanup() throws Exception {try(var c=connection();var s=c.createStatement()){s.execute("DROP SCHEMA IF EXISTS "+SCHEMA+" CASCADE");}}
 @BeforeEach void seedIdentity() {
  // 仅在隔离测试库种入会话，不提供生产跳过登录的接口或固定密码。
  adminId=UUID.randomUUID(); adminToken=UUID.randomUUID().toString(); partnerToken=UUID.randomUUID().toString();
  seed(adminId,"admin",adminToken);seed(UUID.randomUUID(),"partner",partnerToken);
  UUID role=UUID.randomUUID();
  db.update("INSERT INTO role(id,code,name,platform,data_scope,status) VALUES(?,?,?,'admin','ALL','ENABLED')",role,role.toString(),"测试管理员");
  db.update("INSERT INTO user_role VALUES(?,?)",adminId,role);
  for(String p:List.of("schools:read","schools:write","majors:read","majors:write"))db.update("INSERT INTO role_permission VALUES(?,?)",role,p);
 }
 void seed(UUID id,String platform,String token) {
  db.update("INSERT INTO user_account(id,platform,display_name,phone_digest,phone_masked,password_hash,status) VALUES(?,?,?,?,?,?,'ENABLED')",id,platform,"隔离测试",UUID.randomUUID().toString(),"138****0000","not-a-login-password");
  db.update("INSERT INTO auth_session(id,user_id,access_digest,refresh_digest,csrf_digest,auth_version,expires_at,refresh_expires_at) VALUES(?,?,?,?,?,1,now()+interval '15 minutes',now()+interval '7 days')",UUID.randomUUID(),id,digester.digest("access",token),UUID.randomUUID().toString(),UUID.randomUUID().toString());
 }
 @Test void authorizationAndImmediateRevocation() throws Exception {
  mvc.perform(get("/api/v1/admin/schools")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/v1/admin/schools").header("Authorization","Bearer "+partnerToken)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/admin/schools").header("Authorization","Bearer "+adminToken)).andExpect(status().isOk());
  db.update("UPDATE user_account SET auth_version=auth_version+1 WHERE id=?",adminId);
  mvc.perform(get("/api/v1/admin/schools").header("Authorization","Bearer "+adminToken)).andExpect(status().isUnauthorized());
 }
 @Test void crudIdempotencyVersionAndReferenceProtection() throws Exception {
  String key=UUID.randomUUID().toString();
  String body="{\"name\":\"测试学院\",\"code\":\""+UUID.randomUUID()+"\",\"region\":\"浙江\",\"status\":\"ENABLED\"}";
  var first=mvc.perform(post("/api/v1/admin/schools").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(header().string("ETag","\"1\"")).andReturn();
  String saved=first.getResponse().getContentAsString(); String id=json.readTree(saved).get("id").asText();
  mvc.perform(post("/api/v1/admin/schools").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(content().json(saved));
  mvc.perform(post("/api/v1/admin/schools").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).contentType("application/json").content(body.replace("浙江","江苏"))).andExpect(status().isConflict());
  mvc.perform(put("/api/v1/admin/schools/"+id).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"2\"").contentType("application/json").content(body)).andExpect(status().isPreconditionFailed());
  mvc.perform(get("/api/v1/admin/schools").header("Authorization","Bearer "+adminToken).param("q","测试学院")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id));
  String major="{\"name\":\"计算机\",\"code\":\"CS\",\"schoolId\":\""+id+"\",\"level\":\"BACHELOR\",\"status\":\"ENABLED\"}";
  mvc.perform(post("/api/v1/admin/majors").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(major)).andExpect(status().isCreated());
  mvc.perform(delete("/api/v1/admin/schools/"+id).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"")).andExpect(status().isConflict());
  assertEquals(2,db.queryForObject("SELECT count(*) FROM audit_event WHERE actor_id=?",Integer.class,adminId));
  assertEquals(2,db.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id IN (SELECT resource_id FROM audit_event WHERE actor_id=?)",Integer.class,adminId));
 }
}
