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
 @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlers;
 @Autowired JdbcTemplate db;
 @Autowired TokenDigester digester;
 @org.springframework.test.context.bean.override.mockito.MockitoBean cn.zhixiang.platform.identity.SmsGateway sms;
 @Autowired JsonMapper json;
 @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;
 @Autowired cn.zhixiang.platform.operations.OutboxMapper outbox;
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
  for(String p:List.of("schools:read","schools:write","schools:delete","majors:read","majors:write","majors:delete"))db.update("INSERT INTO role_permission VALUES(?,?)",role,p);
 }
 void seed(UUID id,String platform,String token) {
  db.update("INSERT INTO user_account(id,platform,display_name,phone_digest,phone_masked,password_hash,status) VALUES(?,?,?,?,?,?,'ENABLED')",id,platform,"隔离测试",UUID.randomUUID().toString(),"138****0000","not-a-login-password");
  db.update("INSERT INTO auth_session(id,user_id,access_digest,refresh_digest,csrf_digest,auth_version,expires_at,refresh_expires_at,family_id) VALUES(?,?,?,?,?,1,now()+interval '15 minutes',now()+interval '7 days',?)",UUID.randomUUID(),id,digester.digest("access",token),UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID());
 }
 @Test void disabledSchoolPreventsMajorEnable() throws Exception {
  String schoolBody=json.writeValueAsString(Map.of("name","停用保护院校","code",UUID.randomUUID().toString(),"region","浙江","status","ENABLED"));
  var school=mvc.perform(post("/api/v1/admin/schools").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(schoolBody)).andExpect(status().isCreated()).andReturn().getResponse();
  String schoolId=json.readTree(school.getContentAsString()).get("id").asText();
  String majorBody=json.writeValueAsString(Map.of("name","专业","code","TEST","schoolId",schoolId,"level","BACHELOR","status","DISABLED"));
  var major=mvc.perform(post("/api/v1/admin/majors").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(majorBody)).andExpect(status().isCreated()).andReturn().getResponse();
  String majorId=json.readTree(major.getContentAsString()).get("id").asText();
  mvc.perform(post("/api/v1/admin/schools/"+schoolId+"/status").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content("{\"status\":\"DISABLED\"}")).andExpect(status().isOk());
  mvc.perform(post("/api/v1/admin/majors/"+majorId+"/status").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content("{\"status\":\"ENABLED\"}")).andExpect(status().isUnprocessableEntity());
  mvc.perform(get("/api/v1/admin/majors").header("Authorization","Bearer "+adminToken).param("schoolId",schoolId)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
 }
 @Test void registerResetAndSingleUseChallenges() throws Exception {
  var codes=new java.util.concurrent.ConcurrentHashMap<UUID,String>();
  org.mockito.Mockito.doAnswer(call->{codes.put(call.getArgument(0),call.getArgument(2));return null;})
   .when(sms).send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
  String phone="13612345678",password="注册长密码".repeat(15);
  var request=post("/api/v1/auth/challenges").contentType("application/json").content(json.writeValueAsString(Map.of("phone",phone,"purpose","REGISTER")));
  String challengeResponse=mvc.perform(request).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
  UUID challenge=UUID.fromString(json.readTree(challengeResponse).get("challengeId").asText());
  String registration=json.writeValueAsString(Map.of("phone",phone,"password",password,"displayName","新学生","challengeId",challenge,"code",codes.get(challenge),"termsVersion","2026-10-01"));
  var response=mvc.perform(post("/api/v1/auth/student/register").contentType("application/json").content(registration))
    .andExpect(status().isCreated()).andExpect(jsonPath("$.platform").value("student")).andReturn().getResponse();
  String access=json.readTree(response.getContentAsString()).get("accessToken").asText();
  mvc.perform(post("/api/v1/auth/student/register").contentType("application/json").content(registration)).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/auth/student/login").contentType("application/json").content(json.writeValueAsString(Map.of("phone",phone,"password",password)))).andExpect(status().isOk());
  var resetResult=mvc.perform(post("/api/v1/auth/challenges").contentType("application/json").content(json.writeValueAsString(Map.of("phone",phone,"purpose","RESET_PASSWORD","platform","student")))).andExpect(status().isAccepted()).andReturn().getResponse();
  UUID reset=UUID.fromString(json.readTree(resetResult.getContentAsString()).get("challengeId").asText());
  mvc.perform(post("/api/v1/auth/password-reset").contentType("application/json").content(json.writeValueAsString(Map.of("challengeId",reset,"code",codes.get(reset),"newPassword","new-password-0938!")))).andExpect(status().isNoContent());
  mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/auth/student/login").contentType("application/json").content(json.writeValueAsString(Map.of("phone",phone,"password",password)))).andExpect(status().isUnauthorized());
  assertEquals(1,db.queryForObject("SELECT count(*) FROM user_account WHERE phone_digest=? AND platform='student'",Integer.class,digester.digest("phone",phone)));
 }
 @Test void verificationFailureCounterCommitsAndLimitsAttempts() throws Exception {
  var codes=new java.util.concurrent.ConcurrentHashMap<UUID,String>();
  org.mockito.Mockito.doAnswer(call->{codes.put(call.getArgument(0),call.getArgument(2));return null;})
   .when(sms).send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
  String phone="13512345678";
  var response=mvc.perform(post("/api/v1/auth/challenges").contentType("application/json").content(json.writeValueAsString(Map.of("phone",phone,"purpose","REGISTER")))).andExpect(status().isAccepted()).andReturn().getResponse();
  UUID id=UUID.fromString(json.readTree(response.getContentAsString()).get("challengeId").asText());
  String wrong=codes.get(id).equals("000000")?"111111":"000000";
  var input=new java.util.HashMap<String,Object>(Map.of("phone",phone,"password","test-password-833","displayName","失败计数","challengeId",id,"code",wrong,"termsVersion","2026-10-01"));
  for(int i=0;i<5;i++)mvc.perform(post("/api/v1/auth/student/register").contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isUnauthorized());
  assertEquals(5,db.queryForObject("SELECT attempts FROM verification_challenge WHERE id=?",Integer.class,id));
  input.put("code",codes.get(id));
  mvc.perform(post("/api/v1/auth/student/register").contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isUnauthorized());
 }
 @Test void smsFailureCannotBecomeUsableChallenge() throws Exception {
  org.mockito.Mockito.doThrow(new cn.zhixiang.platform.shared.BusinessException(503,"PROVIDER_UNAVAILABLE","短信未就绪"))
    .when(sms).send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
  String phone="13412345678";
  mvc.perform(post("/api/v1/auth/challenges").contentType("application/json").content(json.writeValueAsString(Map.of("phone",phone,"purpose","REGISTER")))).andExpect(status().isServiceUnavailable());
  assertEquals("FAILED",db.queryForObject("SELECT state FROM verification_challenge WHERE phone_digest=?",String.class,digester.digest("phone",phone)));
 }
 @Test void administratorRequiresMfaAndRejectsStepReplay() throws Exception {
  UUID id=UUID.randomUUID(),ref=UUID.randomUUID();String phone="13712345678",password="admin-test-only-938!";
  String hash=org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(password);
  db.update("INSERT INTO user_account(id,platform,display_name,phone_digest,phone_masked,password_hash,status,mfa_secret_ref) VALUES(?,'admin',?,?,?,?,'ENABLED',?)",id,"测试管理员",digester.digest("phone",phone),"137****5678",hash,ref.toString());
  var dir=java.nio.file.Path.of("secrets/mfa");java.nio.file.Files.createDirectories(dir);var file=dir.resolve(ref.toString());
  // RFC标准测试种子只用于测试账户，结束后删除，不为真实用户配置固定种子。
  java.nio.file.Files.writeString(file,"GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",java.nio.file.StandardOpenOption.CREATE_NEW);
  java.nio.file.Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
  try {
   mvc.perform(post("/api/v1/auth/admin/login").contentType("application/json").content(json.writeValueAsString(Map.of("phone",phone,"password",password))))
    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
   String code=cn.zhixiang.platform.identity.TotpVerifier.generate("12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII),java.time.Instant.now().getEpochSecond()/30);
   String input=json.writeValueAsString(Map.of("phone",phone,"password",password,"mfaCode",code));
   mvc.perform(post("/api/v1/auth/admin/login").contentType("application/json").content(input)).andExpect(status().isOk());
   mvc.perform(post("/api/v1/auth/admin/login").contentType("application/json").content(input)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("MFA_INVALID"));
  } finally {java.nio.file.Files.deleteIfExists(file);}
 }
 @Test void concurrentDuplicateWriteHasOneEffect() throws Exception {
  String key=UUID.randomUUID().toString();
  String body=json.writeValueAsString(Map.of("name","并发院校","code",key,"region","浙江","status","ENABLED"));
  var start=new java.util.concurrent.CountDownLatch(1);
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
   java.util.concurrent.Callable<String> call=()->{start.await();return mvc.perform(post("/api/v1/admin/schools").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();};
   var one=pool.submit(call);var two=pool.submit(call);start.countDown();
   assertEquals(one.get(10,java.util.concurrent.TimeUnit.SECONDS),two.get(10,java.util.concurrent.TimeUnit.SECONDS));
  }
  assertEquals(1,db.queryForObject("SELECT count(*) FROM school WHERE code=?",Integer.class,key));
  assertEquals(1,db.queryForObject("SELECT count(*) FROM audit_event WHERE actor_id=?",Integer.class,adminId));
 }
 @Test void realBrokerConfirmMarksOutboxSent() throws Exception {
  String name="zx.test."+UUID.randomUUID();
  var admin=new org.springframework.amqp.rabbit.core.RabbitAdmin(rabbit.getConnectionFactory());
  var exchange=new org.springframework.amqp.core.DirectExchange(name,false,true);
  var queue=new org.springframework.amqp.core.Queue(name,false,false,false);
  admin.declareExchange(exchange);admin.declareQueue(queue);
  admin.declareBinding(org.springframework.amqp.core.BindingBuilder.bind(queue).to(exchange).with("test"));
  UUID event=UUID.randomUUID();
  try {
   db.update("INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES(?,?,?,'{}')",event,UUID.randomUUID(),"test.probe");
   new cn.zhixiang.platform.operations.OutboxPublisher(outbox,rabbit,json,name,"test").publish();
   assertEquals("SENT",db.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,event));
   boolean found=false;org.springframework.amqp.core.Message message;
   while((message=rabbit.receive(name,500))!=null) if(event.toString().equals(message.getMessageProperties().getMessageId()))found=true;
   assertTrue(found,"真实队列必须收到目标事件");
  } finally {admin.deleteQueue(name);admin.deleteExchange(name);}
 }
 @Test void loginRefreshReplayAndCsrf() throws Exception {
  UUID student=UUID.randomUUID();String phone="13912345678",password="test-only-password-928!";
  String hash=org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(password);
  db.update("INSERT INTO user_account(id,platform,display_name,phone_digest,phone_masked,password_hash,status) VALUES(?,'student',?,?,?,?,'ENABLED')",student,"测试学生",digester.digest("phone",phone),"139****5678",hash);
  String input=json.writeValueAsString(Map.of("phone",phone,"password",password));
  var login=mvc.perform(post("/api/v1/auth/student/login").contentType("application/json").content(input))
   .andExpect(status().isOk()).andExpect(cookie().httpOnly("zx_student_refresh",true)).andExpect(cookie().secure("zx_student_refresh",true))
   .andExpect(jsonPath("$.user.platform").value("student")).andReturn().getResponse();
  var data=json.readTree(login.getContentAsString());String access=data.get("accessToken").asText(),csrf=data.get("csrfToken").asText();
  var refresh=login.getCookie("zx_student_refresh");var csrfCookie=login.getCookie("zx_student_csrf");
  mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/admin/schools").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/auth/student/refresh").cookie(refresh,csrfCookie).header("X-CSRF-Token",csrf).header("Origin","https://evil.invalid")).andExpect(status().isForbidden());
  var rotated=mvc.perform(post("/api/v1/auth/student/refresh").cookie(refresh,csrfCookie).header("X-CSRF-Token",csrf).header("Origin","https://student.invalid"))
   .andExpect(status().isOk()).andReturn().getResponse();
  String newAccess=json.readTree(rotated.getContentAsString()).get("accessToken").asText();
  mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/auth/student/refresh").cookie(refresh,csrfCookie).header("X-CSRF-Token",csrf).header("Origin","https://student.invalid"))
   .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REPLAY"));
  // 撤销必须在抛业务异常前提交，验证新令牌也失效。
  mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+newAccess)).andExpect(status().isUnauthorized());
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
 @Test void roleScopeBuiltInAndReferenceProtection() throws Exception {
  UUID superRole=UUID.fromString("00000000-0000-4000-8000-000000000001");
  db.update("INSERT INTO user_role VALUES(?,?)",adminId,superRole);
  try {
   String code="TEST_"+UUID.randomUUID().toString().replace("-","").toUpperCase();
   String body=json.writeValueAsString(Map.of("name","资料维护员","code",code,"platform","admin","dataScope","ALL","permissionCodes",List.of("schools:read"),"status","ENABLED"));
   var created=mvc.perform(post("/api/v1/admin/roles").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse();
   UUID role=UUID.fromString(json.readTree(created.getContentAsString()).get("id").asText());
   mvc.perform(post("/api/v1/admin/roles").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body.replace("ALL","SELF"))).andExpect(status().isUnprocessableEntity());
   mvc.perform(delete("/api/v1/admin/roles/"+superRole).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"")).andExpect(status().isForbidden());
   UUID target=UUID.randomUUID();String token=UUID.randomUUID().toString();seed(target,"admin",token);db.update("INSERT INTO user_role VALUES(?,?)",target,role);
   mvc.perform(delete("/api/v1/admin/roles/"+role).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"")).andExpect(status().isConflict());
   mvc.perform(get("/api/v1/admin/schools").header("Authorization","Bearer "+token)).andExpect(status().isOk());
   mvc.perform(post("/api/v1/admin/roles/"+role+"/status").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content("{\"status\":\"DISABLED\"}")).andExpect(status().isOk());
   mvc.perform(get("/api/v1/admin/schools").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
  } finally {db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?",adminId,superRole);}
 }
 @Test void adminInvitationMfaActivationAndLastAdminProtection() throws Exception {
  UUID superRole=UUID.fromString("00000000-0000-4000-8000-000000000001");
  db.update("INSERT INTO user_role VALUES(?,?)",adminId,superRole);db.update("UPDATE user_account SET activated_at=now() WHERE id=?",adminId);
  UUID createdUser=null;
  try {
   mvc.perform(post("/api/v1/admin/users/"+adminId+"/status").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content("{\"status\":\"DISABLED\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN_REQUIRED"));
   String phone="13512345678";
   var created=mvc.perform(post("/api/v1/admin/users").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(Map.of("phone",phone,"displayName","新管理员","platform","admin","roleIds",List.of(superRole))))).andExpect(status().isCreated()).andReturn().getResponse();
   createdUser=UUID.fromString(json.readTree(created.getContentAsString()).get("id").asText());
   mvc.perform(post("/api/v1/admin/users/"+createdUser+"/status").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content("{\"status\":\"ENABLED\"}")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ACTIVATION_REQUIRED"));
   String path="/api/v1/admin/users/"+createdUser+"/invitations",key=UUID.randomUUID().toString();
   var invited=mvc.perform(post(path).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).contentType("application/json").content("{\"expiresInHours\":1}")).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
   String link=json.readTree(invited.getContentAsString()).get("activationUrl").asText(),token=link.substring(link.indexOf("#token=")+7);
   mvc.perform(post(path).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).contentType("application/json").content("{\"expiresInHours\":1}")).andExpect(status().isCreated()).andExpect(content().json(invited.getContentAsString()));
   assertEquals(0,db.queryForObject("SELECT count(*) FROM idempotency_record WHERE response_body LIKE ?",Integer.class,"%"+token+"%"));
   var enrolled=mvc.perform(post("/api/v1/auth/admin/mfa-enrollments").contentType("application/json").content(json.writeValueAsString(Map.of("invitationToken",token)))).andExpect(status().isCreated()).andReturn().getResponse();
   var enrollment=json.readTree(enrolled.getContentAsString());String uri=enrollment.get("otpAuthUri").asText(),secret=uri.split("secret=")[1].split("&")[0];
   assertEquals(0,db.queryForObject("SELECT count(*) FROM secret_material WHERE ciphertext LIKE ?",Integer.class,"%"+secret+"%"));
   var out=new java.io.ByteArrayOutputStream();int buffer=0,bits=0;for(char c:secret.toCharArray()){buffer=(buffer<<5)|"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);bits+=5;if(bits>=8){bits-=8;out.write((buffer>>bits)&255);}}
   String otp=cn.zhixiang.platform.identity.TotpVerifier.generate(out.toByteArray(),java.time.Instant.now().getEpochSecond()/30);
   String activation=json.writeValueAsString(Map.of("invitationToken",token,"password","admin-test-password-2026","mfaEnrollmentId",enrollment.get("enrollmentId").asText(),"mfaCode",otp));
   var result=mvc.perform(post("/api/v1/auth/admin/activate").contentType("application/json").content(activation)).andExpect(status().isCreated()).andExpect(jsonPath("$.platform").value("admin")).andReturn().getResponse();
   String access=json.readTree(result.getContentAsString()).get("accessToken").asText();
   mvc.perform(get("/api/v1/admin/users").header("Authorization","Bearer "+access)).andExpect(status().isOk());
   mvc.perform(post("/api/v1/auth/admin/activate").contentType("application/json").content(activation)).andExpect(status().isUnauthorized());
   mvc.perform(post("/api/v1/auth/admin/login").contentType("application/json").content(json.writeValueAsString(Map.of("phone",phone,"password","admin-test-password-2026","mfaCode",otp)))).andExpect(status().isUnauthorized());
  } finally {db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?",adminId,superRole);if(createdUser!=null)db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?",createdUser,superRole);}
 }

 @Test void partnerInvitationRequiresPhoneProofAndRejectsReplacedToken() throws Exception {
  UUID superRole=UUID.fromString("00000000-0000-4000-8000-000000000001");db.update("INSERT INTO user_role VALUES(?,?)",adminId,superRole);
  var codes=new java.util.concurrent.ConcurrentHashMap<UUID,String>();
  org.mockito.Mockito.doAnswer(call->{codes.put(call.getArgument(0),call.getArgument(2));return null;}).when(sms).send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
  try {
   String phone="13412345678";
   var invited=mvc.perform(post("/api/v1/admin/partner-invitations").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(Map.of("phone",phone,"displayName","受邀中介","expiresInHours",1)))).andExpect(status().isCreated()).andReturn().getResponse();
   String link=json.readTree(invited.getContentAsString()).get("activationUrl").asText(),old=link.split("#token=")[1];
   UUID user=db.queryForObject("SELECT id FROM user_account WHERE platform='partner' AND phone_digest=?",UUID.class,digester.digest("phone",phone));
   var renewed=mvc.perform(post("/api/v1/admin/users/"+user+"/invitations").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"expiresInHours\":1}")).andExpect(status().isCreated()).andReturn().getResponse();
   String token=json.readTree(renewed.getContentAsString()).get("activationUrl").asText().split("#token=")[1];
   var sent=mvc.perform(post("/api/v1/auth/challenges").contentType("application/json").content(json.writeValueAsString(Map.of("phone",phone,"purpose","ACTIVATE","platform","partner")))).andExpect(status().isAccepted()).andReturn().getResponse();
   UUID challenge=UUID.fromString(json.readTree(sent.getContentAsString()).get("challengeId").asText());
   Map<String,Object> input=new HashMap<>(Map.of("phone",phone,"password","partner-password-2026","displayName","中介","termsVersion","2026-10-01","invitationToken",old,"challengeId",challenge,"code",codes.get(challenge)));
   mvc.perform(post("/api/v1/auth/partner/activate").contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isUnauthorized());
   input.put("invitationToken",token);input.put("code",codes.get(challenge).equals("000000")?"000001":"000000");
   mvc.perform(post("/api/v1/auth/partner/activate").contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isUnauthorized());
   assertEquals(1,db.queryForObject("SELECT attempts FROM verification_challenge WHERE id=?",Integer.class,challenge));
   input.put("code",codes.get(challenge));
   var activated=mvc.perform(post("/api/v1/auth/partner/activate").contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isCreated()).andExpect(jsonPath("$.platform").value("partner")).andReturn().getResponse();
   String access=json.readTree(activated.getContentAsString()).get("accessToken").asText();
   mvc.perform(get("/api/v1/admin/users").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());
   mvc.perform(get("/api/v1/student/conversations").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());
   mvc.perform(post("/api/v1/auth/partner/activate").contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isUnauthorized());
  } finally {db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?",adminId,superRole);}
 }
 @Test void delegatedRoleWriterCannotEscalateOrEditHigherRole() throws Exception {
  UUID ownRole=db.queryForObject("SELECT role_id FROM user_role WHERE user_id=?",UUID.class,adminId);
  db.update("INSERT INTO role_permission VALUES(?,'roles:write')",ownRole);
  String body=json.writeValueAsString(Map.of("name","越权角色","code","ESC_"+UUID.randomUUID().toString().replace("-","").toUpperCase(),"platform","admin","dataScope","ALL","permissionCodes",List.of("post_admin_users"),"status","ENABLED"));
  mvc.perform(post("/api/v1/admin/roles").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isForbidden());
  UUID higher=UUID.randomUUID();db.update("INSERT INTO role(id,code,name,platform,data_scope,status) VALUES(?,?,?,'admin','ALL','ENABLED')",higher,higher.toString(),"高权限");db.update("INSERT INTO role_permission VALUES(?,'post_admin_users')",higher);
  String lower=body.replace("post_admin_users","schools:read");
  mvc.perform(put("/api/v1/admin/roles/"+higher).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content(lower)).andExpect(status().isForbidden());
 }

 @Test void exportImplementedRoutesForContractReview() throws Exception {
  var rows=new java.util.ArrayList<Map<String,String>>();
  handlers.getHandlerMethods().forEach((mapping,method)->{
   var permission=method.getMethodAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
   for(String path:mapping.getPatternValues())if(path.startsWith("/api/v1/"))for(var verb:mapping.getMethodsCondition().getMethods()) {
    List<String> paths=path.contains("{platform:")?List.of(path.replaceAll("\\{platform:[^}]+}","student"),path.replaceAll("\\{platform:[^}]+}","partner"),path.replaceAll("\\{platform:[^}]+}","admin")):List.of(path);
    for(String concrete:paths)rows.add(Map.of("method",verb.name(),"path",concrete,"permission",permission==null?"":permission.value()));
   }
  });
  rows.sort(java.util.Comparator.comparing(r->r.get("path")+r.get("method")));
  java.nio.file.Files.writeString(java.nio.file.Path.of("target/implemented-routes.json"),json.writeValueAsString(rows));
  assertTrue(rows.size()>30);
 }

}
