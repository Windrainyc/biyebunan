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
 @Autowired cn.zhixiang.platform.storage.ObjectStorage storage;
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

 @Test void membershipSnapshotsVersioningAndRevocation() throws Exception {
  UUID superRole=UUID.fromString("00000000-0000-4000-8000-000000000001");db.update("INSERT INTO user_role VALUES(?,?)",adminId,superRole);
  try {
   UUID student=UUID.randomUUID();String token=UUID.randomUUID().toString();seed(student,"student",token);db.update("INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')",student);
   String body=json.writeValueAsString(Map.of("name","标准答疑会员","code",UUID.randomUUID().toString(),"durationDays",30,"monthlyTokenQuota",100000,"monthlyAmountLimit","20.123456","status","ENABLED"));
   var plan=mvc.perform(post("/api/v1/admin/membership-plans").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.monthlyAmountLimit").value("20.123456")).andReturn().getResponse();
   UUID planId=UUID.fromString(json.readTree(plan.getContentAsString()).get("id").asText());
   String grant=json.writeValueAsString(Map.of("planId",planId,"startsAt",java.time.OffsetDateTime.now().minusMinutes(1).toString(),"reason","测试发放"));String path="/api/v1/admin/users/"+student+"/memberships",key=UUID.randomUUID().toString();
   var member=mvc.perform(post(path).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).contentType("application/json").content(grant)).andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("ACTIVE")).andReturn().getResponse();
   UUID memberId=UUID.fromString(json.readTree(member.getContentAsString()).get("id").asText());UUID originalRevision=db.queryForObject("SELECT plan_version_id FROM membership WHERE id=?",UUID.class,memberId);
   mvc.perform(post(path).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).contentType("application/json").content(grant)).andExpect(status().isCreated()).andExpect(content().json(member.getContentAsString()));
   mvc.perform(get("/api/v1/student/membership").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(memberId.toString()));
   mvc.perform(put("/api/v1/admin/membership-plans/"+planId).header("Authorization","Bearer "+adminToken).header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body.replace("100000","200000"))).andExpect(status().isUnprocessableEntity());
   String revision=json.writeValueAsString(Map.of("durationDays",60,"monthlyTokenQuota",200000,"monthlyAmountLimit","30.5","effectiveFrom",java.time.OffsetDateTime.now().plusDays(1).toString()));
   mvc.perform(post("/api/v1/admin/membership-plans/"+planId+"/versions").header("Authorization","Bearer "+adminToken).header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(revision)).andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2));
   assertEquals(originalRevision,db.queryForObject("SELECT plan_version_id FROM membership WHERE id=?",UUID.class,memberId));
   mvc.perform(get("/api/v1/admin/membership-plans/"+planId).header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.monthlyTokenQuota").value(100000)).andExpect(header().string("ETag","\"2\""));
   mvc.perform(get("/api/v1/admin/membership-plans/"+planId+"/versions").header("Authorization","Bearer "+adminToken).param("size","1").param("sort","createdAt:asc")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[0].version").value(1));
   mvc.perform(delete("/api/v1/admin/membership-plans/"+planId).header("Authorization","Bearer "+adminToken).header("If-Match","\"2\"").header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isConflict());
   mvc.perform(post("/api/v1/admin/memberships/"+memberId+"/revoke").header("Authorization","Bearer "+adminToken).header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"reason\":\"撤销测试\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REVOKED"));
   mvc.perform(get("/api/v1/student/membership").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
   mvc.perform(get(path).header("Authorization","Bearer "+adminToken).param("status","REVOKED").param("q","标准答疑")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
   assertEquals("撤销测试",db.queryForObject("SELECT detail->>'reason' FROM audit_event WHERE resource_id=? AND action='REVOKED'",String.class,memberId));
  }finally{db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?",adminId,superRole);}
 }
 @Test void concurrentMembershipGrantsNeverOverlapAndScheduledIsNotActive() throws Exception {
  UUID superRole=UUID.fromString("00000000-0000-4000-8000-000000000001");db.update("INSERT INTO user_role VALUES(?,?)",adminId,superRole);
  try {
   UUID student=UUID.randomUUID();String token=UUID.randomUUID().toString();seed(student,"student",token);db.update("INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')",student);
   String body=json.writeValueAsString(Map.of("name","未来会员","code",UUID.randomUUID().toString(),"durationDays",10,"monthlyTokenQuota",1000,"monthlyAmountLimit","1","status","ENABLED"));
   var response=mvc.perform(post("/api/v1/admin/membership-plans").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse();
   UUID plan=UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());String grant=json.writeValueAsString(Map.of("planId",plan,"startsAt",java.time.OffsetDateTime.now().plusDays(1).toString(),"reason","并发测试"));
   var start=new java.util.concurrent.CountDownLatch(1);
   try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
    java.util.concurrent.Callable<Integer> call=()->{start.await();return mvc.perform(post("/api/v1/admin/users/"+student+"/memberships").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(grant)).andReturn().getResponse().getStatus();};
    var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertEquals(Set.of(201,409),Set.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS)));
   }
   assertEquals(1,db.queryForObject("SELECT count(*) FROM membership WHERE user_id=?",Integer.class,student));
   mvc.perform(get("/api/v1/admin/users/"+student+"/memberships").header("Authorization","Bearer "+adminToken).param("status","SCHEDULED")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
   mvc.perform(get("/api/v1/student/membership").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
   mvc.perform(get("/api/v1/admin/membership-plans").header("Authorization","Bearer "+partnerToken)).andExpect(status().isForbidden());
  }finally{db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?",adminId,superRole);}
 }

 @Test void menuTreeScopePermissionFilteringAndCycleProtection() throws Exception {
  UUID superRole=UUID.fromString("00000000-0000-4000-8000-000000000001");db.update("INSERT INTO user_role VALUES(?,?)",adminId,superRole);
  try {
   UUID student=UUID.randomUUID();String token=UUID.randomUUID().toString();seed(student,"student",token);
   UUID role=UUID.randomUUID();db.update("INSERT INTO role(id,code,name,platform,data_scope,status) VALUES(?,?,?,'student','SELF','ENABLED')",role,role.toString(),"菜单受限角色");db.update("INSERT INTO user_role VALUES(?,?)",student,role);
   for(String permission:List.of("get_student_dictionaries_menus","get_student_membership"))db.update("INSERT INTO role_permission VALUES(?,?)",role,permission);
   String unique=UUID.randomUUID().toString();Map<String,Object> root=new HashMap<>();root.put("name","会员入口");root.put("code",unique);root.put("platform","student");root.put("parentId",null);root.put("path","/membership-"+unique);root.put("permissionCode","get_student_membership");root.put("sortOrder",0);root.put("status","ENABLED");
   var created=mvc.perform(post("/api/v1/admin/menus").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(root))).andExpect(status().isCreated()).andReturn().getResponse();
   String parent=json.readTree(created.getContentAsString()).get("id").asText();Map<String,Object> child=new HashMap<>(root);child.put("name","需另一权限");child.put("code",unique+"child");child.put("path","/child-"+unique);child.put("parentId",parent);child.put("permissionCode","get_student_dictionaries_menus");
   var childResponse=mvc.perform(post("/api/v1/admin/menus").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(child))).andExpect(status().isCreated()).andReturn().getResponse();String childId=json.readTree(childResponse.getContentAsString()).get("id").asText();
   mvc.perform(get("/api/v1/student/dictionaries/menus").header("Authorization","Bearer "+token).param("q",unique)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
   root.put("parentId",childId);
   mvc.perform(put("/api/v1/admin/menus/"+parent).header("Authorization","Bearer "+adminToken).header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(root))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MENU_CYCLE"));
   mvc.perform(post("/api/v1/admin/menus/"+parent+"/status").header("Authorization","Bearer "+adminToken).header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"status\":\"DISABLED\"}")).andExpect(status().isConflict());
   mvc.perform(delete("/api/v1/admin/menus/"+parent).header("Authorization","Bearer "+adminToken).header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isConflict());
   // 删除父项权限后，拥有子项权限也不能绕过整条祖先链。
   db.update("DELETE FROM role_permission WHERE role_id=? AND permission_code='get_student_membership'",role);
   mvc.perform(get("/api/v1/student/dictionaries/menus").header("Authorization","Bearer "+token).param("q",unique)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
   child.put("permissionCode","menus:write");
   mvc.perform(post("/api/v1/admin/menus").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(child))).andExpect(status().isUnprocessableEntity());
   child.put("path","//evil.invalid");
   mvc.perform(post("/api/v1/admin/menus").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(child))).andExpect(status().isBadRequest());
  }finally{db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?",adminId,superRole);}
 }

 @Test void taxonomyCrudLiteralSearchAndTreeInvariants() throws Exception {
  UUID superRole=UUID.fromString("00000000-0000-4000-8000-000000000001");db.update("INSERT INTO user_role VALUES(?,?)",adminId,superRole);
  try {
   String code="tag_%_"+UUID.randomUUID();String input=json.writeValueAsString(Map.of("name","精确标签","code",code,"status","ENABLED"));
   var tag=mvc.perform(post("/api/v1/admin/tags").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(input)).andExpect(status().isCreated()).andReturn().getResponse();String id=json.readTree(tag.getContentAsString()).get("id").asText();
   mvc.perform(get("/api/v1/admin/tags").header("Authorization","Bearer "+adminToken).param("q",code)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
   mvc.perform(post("/api/v1/admin/tags").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(input)).andExpect(status().isConflict());
   mvc.perform(put("/api/v1/admin/tags/"+id).header("Authorization","Bearer "+adminToken).header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(input.replace("精确标签","更新标签"))).andExpect(status().isOk()).andExpect(header().string("ETag","\"2\""));
   mvc.perform(post("/api/v1/admin/tags/"+id+"/status").header("Authorization","Bearer "+adminToken).header("If-Match","\"2\"").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"status\":\"DISABLED\"}")).andExpect(status().isOk());
   UUID student=UUID.randomUUID();String token=UUID.randomUUID().toString();seed(student,"student",token);db.update("INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')",student);
   mvc.perform(get("/api/v1/student/dictionaries/tags").header("Authorization","Bearer "+token).param("q",code)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
   mvc.perform(delete("/api/v1/admin/tags/"+id).header("Authorization","Bearer "+adminToken).header("If-Match","\"3\"").header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isNoContent());
   Map<String,Object> root=new HashMap<>();root.put("name","知识根分类");root.put("code",UUID.randomUUID().toString());root.put("parentId",null);root.put("sortOrder",1);root.put("status","ENABLED");
   var category=mvc.perform(post("/api/v1/admin/categories").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(root))).andExpect(status().isCreated()).andReturn().getResponse();String parent=json.readTree(category.getContentAsString()).get("id").asText();
   Map<String,Object> child=new HashMap<>(root);child.put("parentId",parent);child.put("code",UUID.randomUUID().toString());
   var createdChild=mvc.perform(post("/api/v1/admin/categories").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(child))).andExpect(status().isCreated()).andReturn().getResponse();String childId=json.readTree(createdChild.getContentAsString()).get("id").asText();
   root.put("parentId",childId);
   mvc.perform(put("/api/v1/admin/categories/"+parent).header("Authorization","Bearer "+adminToken).header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(root))).andExpect(status().isUnprocessableEntity());
   mvc.perform(delete("/api/v1/admin/categories/"+parent).header("Authorization","Bearer "+adminToken).header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isConflict());
   mvc.perform(post("/api/v1/admin/categories/"+parent+"/status").header("Authorization","Bearer "+adminToken).header("If-Match","\"1\"").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"status\":\"DISABLED\"}")).andExpect(status().isConflict());
   mvc.perform(get("/api/v1/student/dictionaries/categories").header("Authorization","Bearer "+token).param("status","DISABLED")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
  }finally{db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?",adminId,superRole);}
 }


 @Test void modelConfigurationValidationAndCas() throws Exception {
  UUID superRole=UUID.fromString("00000000-0000-4000-8000-000000000001");db.update("INSERT INTO user_role VALUES(?,?)",adminId,superRole);
  try {
   Map<String,Object> body=new HashMap<>(Map.of("name","测试模型","code",UUID.randomUUID().toString(),"provider","OPENAI_COMPATIBLE","endpoint","https://example.invalid/v1","contextLimit",8192,"maxOutputTokens",1024,"status","ENABLED"));
   String path="/api/v1/admin/models",key=UUID.randomUUID().toString();
   var response=mvc.perform(post(path).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isCreated()).andReturn().getResponse();
   String id=json.readTree(response.getContentAsString()).get("id").asText();
   mvc.perform(post(path).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isCreated()).andExpect(content().json(response.getContentAsString()));
   body.put("maxOutputTokens",8193);
   mvc.perform(put(path+"/"+id).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isUnprocessableEntity());
   body.put("maxOutputTokens",2048);body.put("endpoint","https://secret@example.invalid/v1");
   mvc.perform(put(path+"/"+id).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isUnprocessableEntity());
   body.put("endpoint","https://example.invalid/v1");
   mvc.perform(put(path+"/"+id).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
   mvc.perform(delete(path+"/"+id).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"")).andExpect(status().isPreconditionFailed());
   mvc.perform(get(path).header("Authorization","Bearer "+partnerToken)).andExpect(status().isForbidden());
   mvc.perform(get(path).header("Authorization","Bearer "+adminToken).param("q",body.get("code").toString())).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
   mvc.perform(delete(path+"/"+id).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"2\"")).andExpect(status().isNoContent());
  } finally {db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?",adminId,superRole);}
 }

 @Test void modelPriceHistoryAndSecretRotation() throws Exception {
  UUID superRole=UUID.fromString("00000000-0000-4000-8000-000000000001");db.update("INSERT INTO user_role VALUES(?,?)",adminId,superRole);
  try {
   String models="/api/v1/admin/models",prices="/api/v1/admin/model-prices",keys="/api/v1/admin/model-keys";
   Map<String,Object> model=new HashMap<>(Map.of("name","带价格的模型","code",UUID.randomUUID().toString(),"provider","OPENAI_COMPATIBLE","endpoint","https://example.invalid/v1","contextLimit",8192,"maxOutputTokens",1024,"status","ENABLED"));
   String mid=json.readTree(mvc.perform(post(models).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(model))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
   var price=new HashMap<String,Object>(Map.of("modelId",mid,"currency","CNY","inputPerMillion","1.123456","outputPerMillion","2.500000","cachedInputPerMillion","0.100000","effectiveFrom",java.time.OffsetDateTime.now().plusHours(1).toString(),"status","ENABLED"));
   var response=mvc.perform(post(prices).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(price))).andExpect(status().isCreated()).andExpect(jsonPath("$.inputPerMillion").value("1.123456")).andReturn().getResponse();
   String pid=json.readTree(response.getContentAsString()).get("id").asText();
   mvc.perform(get(prices).header("Authorization","Bearer "+adminToken).param("q",model.get("code").toString())).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
   model.put("endpoint","https://another.invalid/v1");
   mvc.perform(put(models+"/"+mid).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content(json.writeValueAsString(model))).andExpect(status().isConflict());
   mvc.perform(delete(models+"/"+mid).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"")).andExpect(status().isConflict());
   // 仅隔离 schema 推进生效时间，避免以睡眠测试时间敏感规则。
   db.update("UPDATE model_price SET effective_from=now()-interval '1 minute' WHERE id=?",UUID.fromString(pid));
   mvc.perform(put(prices+"/"+pid).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content(json.writeValueAsString(price))).andExpect(status().isConflict());
   mvc.perform(delete(prices+"/"+pid).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"")).andExpect(status().isConflict());
   String secret="isolated-test-secret-"+UUID.randomUUID();
   String keyBody=json.writeValueAsString(Map.of("name","测试凭证","modelId",mid,"secretValue",secret));
   var kr=mvc.perform(post(keys).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(keyBody)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DISABLED")).andExpect(jsonPath("$.secretValue").doesNotExist()).andReturn().getResponse();
   String kid=json.readTree(kr.getContentAsString()).get("id").asText();assertFalse(kr.getContentAsString().contains(secret));
   UUID oldRef=db.queryForObject("SELECT secret_ref FROM model_key WHERE id=?",UUID.class,UUID.fromString(kid));
   String ciphertext=db.queryForObject("SELECT ciphertext FROM secret_material WHERE id=?",String.class,oldRef);assertFalse(ciphertext.contains(secret));
   String rotated="isolated-new-secret-"+UUID.randomUUID(),idem=UUID.randomUUID().toString();String rotation=json.writeValueAsString(Map.of("secretValue",rotated,"reason","例行轮换"));
   for(int n=0;n<2;n++)mvc.perform(post(keys+"/"+kid+"/rotate").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",idem).header("If-Match","\"1\"").contentType("application/json").content(rotation)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
   assertEquals(0,db.queryForObject("SELECT count(*) FROM secret_material WHERE id=?",Integer.class,oldRef));
   mvc.perform(get(keys).header("Authorization","Bearer "+adminToken).param("q","测试凭证")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].secretRef").doesNotExist());
   mvc.perform(delete(keys+"/"+kid).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"2\"")).andExpect(status().isNoContent());
  } finally {db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?",adminId,superRole);}
 }

 @Test void storageAndDomainConfigurationBoundaries() throws Exception {
  UUID superRole=UUID.fromString("00000000-0000-4000-8000-000000000001");db.update("INSERT INTO user_role VALUES(?,?)",adminId,superRole);
  try {
   String path="/api/v1/admin/storages";
   var body=new HashMap<String,Object>(Map.of("name","本地私有存储","provider","S3_COMPATIBLE","bucket","test-"+UUID.randomUUID(),"region","us-east-1","endpoint","http://127.0.0.1:19000","credentialRef","local-minio","access","PRIVATE","status","ENABLED"));
   var response=mvc.perform(post(path).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isCreated()).andReturn().getResponse();
   String id=json.readTree(response.getContentAsString()).get("id").asText();
   body.put("endpoint","http://external.invalid");
   mvc.perform(put(path+"/"+id).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isUnprocessableEntity());
   body.put("endpoint","https://storage.invalid");
   mvc.perform(put(path+"/"+id).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isConflict());
   mvc.perform(post(path+"/"+id+"/status").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content("{\"status\":\"DISABLED\"}")).andExpect(status().isOk());
   mvc.perform(delete(path+"/"+id).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"2\"")).andExpect(status().isNoContent());
   String domains="/api/v1/admin/domains",host="ZX-"+UUID.randomUUID()+".EXAMPLE.COM";
   var domain=new HashMap<String,Object>(Map.of("name","学生域名","hostname",host,"platform","student","certificateRef","zx-student-tls","status","DISABLED"));
   var dr=mvc.perform(post(domains).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(domain))).andExpect(status().isCreated()).andExpect(jsonPath("$.hostname").value(host.toLowerCase(java.util.Locale.ROOT))).andReturn().getResponse();
   domain.put("hostname",host.toLowerCase(java.util.Locale.ROOT));
   mvc.perform(post(domains).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(domain))).andExpect(status().isConflict());
   domain.put("hostname","-bad.example.com");
   mvc.perform(post(domains).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(domain))).andExpect(status().isUnprocessableEntity());
   mvc.perform(get(domains).header("Authorization","Bearer "+adminToken).param("q","学生域名").param("status","DISABLED")).andExpect(status().isOk());
   mvc.perform(delete(domains+"/"+json.readTree(dr.getContentAsString()).get("id").asText()).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"")).andExpect(status().isNoContent());
  } finally {db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?",adminId,superRole);}
 }

 @Test void orderDraftScopeReassignmentAndReferences() throws Exception {
  UUID superRole=UUID.fromString("00000000-0000-4000-8000-000000000001");db.update("INSERT INTO user_role VALUES(?,?)",adminId,superRole);
  try {
   UUID p1=UUID.randomUUID(),p2=UUID.randomUUID(),student=UUID.randomUUID();String t1=UUID.randomUUID().toString(),t2=UUID.randomUUID().toString(),ts=UUID.randomUUID().toString();
   seed(p1,"partner",t1);seed(p2,"partner",t2);seed(student,"student",ts);
   for(UUID p:List.of(p1,p2)){db.update("INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000003')",p);db.update("UPDATE user_account SET activated_at=now() WHERE id=?",p);}
   db.update("INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')",student);
   UUID school=UUID.randomUUID(),major=UUID.randomUUID();
   db.update("INSERT INTO school(id,name,code,region,status,created_by,updated_by) VALUES(?,'订单院校',?,'浙江','ENABLED',?,?)",school,school.toString(),adminId,adminId);
   db.update("INSERT INTO major(id,name,code,school_id,level,status,created_by,updated_by) VALUES(?,'订单专业',?,?,'BACHELOR','ENABLED',?,?)",major,major.toString(),school,adminId,adminId);
   String base="/api/v1/admin/orders",number="ZX-"+UUID.randomUUID();
   var body=new HashMap<String,Object>(Map.of("title","隔离订单","orderNo",number,"studentPhone","13812340000","partnerId",p1,"amount","1999.123456","currency","CNY","packageCode","STANDARD","source","MANUAL","schoolId",school));body.put("majorId",major);
   var response=mvc.perform(post(base).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.studentPhone").doesNotExist()).andReturn().getResponse();
   String id=json.readTree(response.getContentAsString()).get("id").asText();
   assertFalse(db.queryForObject("SELECT student_phone_ciphertext FROM software_order WHERE id=?",String.class,UUID.fromString(id)).contains("13812340000"));
   mvc.perform(get("/api/v1/partner/orders/"+id).header("Authorization","Bearer "+t1)).andExpect(status().isOk());
   mvc.perform(get("/api/v1/partner/orders/"+id).header("Authorization","Bearer "+t2)).andExpect(status().isNotFound());
   mvc.perform(get("/api/v1/student/orders/"+id).header("Authorization","Bearer "+ts)).andExpect(status().isNotFound());
   mvc.perform(get("/api/v1/partner/orders").header("Authorization","Bearer "+t2).param("q",number)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
   String patch=json.writeValueAsString(Map.of("schoolId",school,"majorId",major,"note","更新备注"));
   mvc.perform(patch("/api/v1/partner/orders/"+id).header("Authorization","Bearer "+t2).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content(patch)).andExpect(status().isNotFound());
   mvc.perform(patch("/api/v1/partner/orders/"+id).header("Authorization","Bearer "+t1).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content(patch)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
   mvc.perform(post(base+"/"+id+"/assignment").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"2\"").contentType("application/json").content(json.writeValueAsString(Map.of("partnerId",p2,"reason","业务移交")))).andExpect(status().isOk());
   mvc.perform(get("/api/v1/partner/orders/"+id).header("Authorization","Bearer "+t1)).andExpect(status().isNotFound());
   mvc.perform(get("/api/v1/partner/orders/"+id).header("Authorization","Bearer "+t2)).andExpect(status().isOk());
   mvc.perform(delete("/api/v1/admin/majors/"+major).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"")).andExpect(status().isConflict());
   var partnerBody=Map.of("title","中介下单","studentPhone","13812340001","schoolId",school,"majorId",major,"packageCode","STANDARD","note","待报价");
   mvc.perform(post("/api/v1/partner/orders").header("Authorization","Bearer "+t1).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(partnerBody))).andExpect(status().isCreated()).andExpect(jsonPath("$.partnerId").value(p1.toString())).andExpect(jsonPath("$.amount").value("0.000000"));
  } finally {db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?",adminId,superRole);}
 }

 @Test void orderBindingProofFailureCommitAndConcurrentConsumption() throws Exception {
  UUID student=UUID.randomUUID(),other=UUID.randomUUID();String token=UUID.randomUUID().toString(),otherToken=UUID.randomUUID().toString(),phone="13900123456";
  seed(student,"student",token);seed(other,"student",otherToken);
  for(UUID user:List.of(student,other))db.update("INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')",user);
  db.update("UPDATE user_account SET phone_digest=?,phone_verified_at=now(),activated_at=now() WHERE id=?",digester.digest("phone",phone),student);
  UUID school=UUID.randomUUID(),major=UUID.randomUUID(),order=UUID.randomUUID();String number="BIND-"+UUID.randomUUID();
  db.update("INSERT INTO school(id,name,code,region,status,created_by,updated_by) VALUES(?,'绑定院校',?,'浙江','ENABLED',?,?)",school,school.toString(),adminId,adminId);
  db.update("INSERT INTO major(id,name,code,school_id,level,status,created_by,updated_by) VALUES(?,'绑定专业',?,?,'BACHELOR','ENABLED',?,?)",major,major.toString(),school,adminId,adminId);
  // 隔离状态机夹具，仅测试绑定；生产确认/收款流程仍需另外验收。
  db.update("INSERT INTO software_order(id,order_no,title,student_phone_digest,student_phone_ciphertext,amount,currency,package_code,source,school_id,major_id,status,created_by,updated_by) VALUES(?,?,'绑定订单',?,'isolated-cipher-placeholder',100,'CNY','STANDARD','MANUAL',?,?,'CONFIRMED',?,?)",order,number,digester.digest("phone",phone),school,major,adminId,adminId);
  var codes=new java.util.concurrent.ConcurrentHashMap<UUID,String>();
  org.mockito.Mockito.doAnswer(call->{codes.put(call.getArgument(0),call.getArgument(2));return null;}).when(sms).send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
  String challengeBody=json.writeValueAsString(Map.of("phone",phone,"purpose","BIND_ORDER","orderNo",number));
  mvc.perform(post("/api/v1/auth/challenges").contentType("application/json").content(challengeBody)).andExpect(status().isUnauthorized());
  String first=mvc.perform(post("/api/v1/auth/challenges").header("Authorization","Bearer "+token).contentType("application/json").content(challengeBody)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
  UUID challenge=UUID.fromString(json.readTree(first).get("challengeId").asText());String path="/api/v1/student/order-bindings";
  String bad=codes.get(challenge).equals("999999")?"888888":"999999";
  for(int n=0;n<5;n++)mvc.perform(post(path).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(Map.of("orderNo",number,"challengeId",challenge,"code",bad)))).andExpect(status().isUnauthorized());
  assertEquals(5,db.queryForObject("SELECT attempts FROM order_binding_challenge WHERE id=?",Integer.class,challenge));
  String failureKey=UUID.randomUUID().toString();String failedBody=json.writeValueAsString(Map.of("orderNo",number,"challengeId",challenge,"code",bad));
  for(int n=0;n<2;n++)mvc.perform(post(path).header("Authorization","Bearer "+token).header("Idempotency-Key",failureKey).contentType("application/json").content(failedBody)).andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));

  mvc.perform(post(path).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(Map.of("orderNo",number,"challengeId",challenge,"code",codes.get(challenge))))).andExpect(status().isUnauthorized());
  String second=mvc.perform(post("/api/v1/auth/challenges").header("Authorization","Bearer "+token).contentType("application/json").content(challengeBody)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
  UUID fresh=UUID.fromString(json.readTree(second).get("challengeId").asText());String binding=json.writeValueAsString(Map.of("orderNo",number,"challengeId",fresh,"code",codes.get(fresh)));
  mvc.perform(post(path).header("Authorization","Bearer "+otherToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(binding)).andExpect(status().isUnauthorized());
  var start=new java.util.concurrent.CountDownLatch(1);String key=UUID.randomUUID().toString();
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
   java.util.concurrent.Callable<Integer> call=()->{start.await();return mvc.perform(post(path).header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType("application/json").content(binding)).andReturn().getResponse().getStatus();};
   var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertEquals(200,a.get());assertEquals(200,b.get());
  }
  assertEquals(student,db.queryForObject("SELECT student_id FROM software_order WHERE id=?",UUID.class,order));
  assertEquals(2L,db.queryForObject("SELECT version FROM software_order WHERE id=?",Long.class,order));
  assertEquals(1,db.queryForObject("SELECT count(*) FROM audit_event WHERE resource_id=? AND action='BOUND'",Integer.class,order));
  assertEquals("CONSUMED",db.queryForObject("SELECT state FROM order_binding_challenge WHERE id=?",String.class,fresh));
  mvc.perform(get("/api/v1/student/orders/"+order).header("Authorization","Bearer "+token)).andExpect(status().isOk());
  mvc.perform(post(path).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(binding)).andExpect(status().isUnauthorized());
 }

 @Test void realMinioSignedUploadIntegrityAndPrivateCopy() throws Exception {
  var target=new cn.zhixiang.platform.storage.ObjectStorage.Target("S3_COMPATIBLE",System.getenv("ZX_S3_ENDPOINT"),System.getenv("ZX_S3_REGION"),"zx-quarantine-private","local-minio");
  String prefix="zx-test/"+UUID.randomUUID(),key=prefix+"/upload",finalKey=prefix+"/private";
  byte[] data="知项独立对象存储协议验证".getBytes(java.nio.charset.StandardCharsets.UTF_8);String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
  var client=java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
  cn.zhixiang.platform.storage.ObjectStorage.Metadata original=null,copy=null;
  try {
   var grant=storage.upload(target,key,data.length,"text/plain",sha);assertFalse(grant.toString().contains("X-Amz"));
   var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(grant.url())).PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(data));
   grant.headers().forEach((name,value)->{if(!name.equalsIgnoreCase("Content-Length"))request.header(name,value);});
   assertEquals(200,client.send(request.build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
   original=storage.stat(target,key);assertEquals(data.length,original.size());assertEquals(sha,storage.sha256(target,key,original));
   var badRequest=java.net.http.HttpRequest.newBuilder(java.net.URI.create(grant.url())).PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(new byte[data.length]));
   grant.headers().forEach((name,value)->{if(!name.equalsIgnoreCase("Content-Length"))badRequest.header(name,value);});
   int badStatus=client.send(badRequest.build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();assertTrue(badStatus==400||badStatus==403);
   copy=storage.promote(target,key,original,finalKey);assertEquals(sha,storage.sha256(target,finalKey,copy));
   var anonymous=java.net.http.HttpRequest.newBuilder(java.net.URI.create(target.endpoint()+"/"+target.bucket()+"/"+finalKey)).GET().build();
   assertEquals(403,client.send(anonymous,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
   var download=storage.download(target,finalKey,copy.versionId());
   var bytes=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(download.url())).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,bytes.statusCode());assertArrayEquals(data,bytes.body());
   assertThrows(cn.zhixiang.platform.shared.BusinessException.class,()->storage.stat(new cn.zhixiang.platform.storage.ObjectStorage.Target("S3_COMPATIBLE","https://untrusted.invalid",target.region(),target.bucket(),"local-minio"),key));
  } finally {if(copy!=null)storage.remove(target,finalKey,copy.versionId());if(original!=null)storage.remove(target,key,original.versionId());}
 }

 @Test void projectOrderTransitionScopeAndConcurrentMilestoneWeights() throws Exception {
  UUID superRole=UUID.fromString("00000000-0000-4000-8000-000000000001");db.update("INSERT INTO user_role VALUES(?,?)",adminId,superRole);
  UUID admin2=UUID.randomUUID();String token2=UUID.randomUUID().toString();seed(admin2,"admin",token2);db.update("INSERT INTO user_role VALUES(?,?)",admin2,superRole);
  try {
   UUID school=UUID.randomUUID(),major=UUID.randomUUID(),student=UUID.randomUUID();String st=UUID.randomUUID().toString();seed(student,"student",st);db.update("INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')",student);
   db.update("INSERT INTO school(id,name,code,region,status,created_by,updated_by) VALUES(?,'项目院校',?,'浙江','ENABLED',?,?)",school,school.toString(),adminId,adminId);
   db.update("INSERT INTO major(id,name,code,school_id,level,status,created_by,updated_by) VALUES(?,'项目专业',?,?,'BACHELOR','ENABLED',?,?)",major,major.toString(),school,adminId,adminId);
   var orderBody=new HashMap<String,Object>(Map.of("title","项目订单","orderNo","PROJECT-"+UUID.randomUUID(),"studentPhone","13812341111","amount","100.00","currency","CNY","packageCode","STANDARD","source","MANUAL","schoolId",school,"majorId",major));orderBody.put("partnerId",null);
   String order=json.readTree(mvc.perform(post("/api/v1/admin/orders").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(orderBody))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
   String path="/api/v1/admin/projects",date=java.time.LocalDate.now().plusDays(30).toString();var body=Map.of("orderId",order,"title","项目题目","requirements","服务端维护的项目需求","deliveryDate",date);
   mvc.perform(post(path).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isUnprocessableEntity());
   mvc.perform(post("/api/v1/admin/orders/"+order+"/transition").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content("{\"action\":\"CONFIRM\",\"reason\":\"已核实资料\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.receiptStatus").value("UNCONFIRMED"));
   String project=json.readTree(mvc.perform(post(path).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
   mvc.perform(post(path).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isConflict());
   mvc.perform(get("/api/v1/student/projects/"+project).header("Authorization","Bearer "+st)).andExpect(status().isNotFound());
   UUID partner=UUID.randomUUID();String partnerAccess=UUID.randomUUID().toString();seed(partner,"partner",partnerAccess);db.update("INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000003')",partner);
   mvc.perform(get("/api/v1/partner/projects/"+project+"/progress").header("Authorization","Bearer "+partnerAccess)).andExpect(status().isNotFound());
   db.update("UPDATE software_order SET partner_id=? WHERE id=?",partner,UUID.fromString(order));
   mvc.perform(get("/api/v1/partner/projects/"+project+"/progress").header("Authorization","Bearer "+partnerAccess)).andExpect(status().isOk()).andExpect(jsonPath("$.requirements").doesNotExist()).andExpect(jsonPath("$.milestones").isArray());
   mvc.perform(get("/api/v1/partner/projects").header("Authorization","Bearer "+partnerAccess).param("q","项目题目")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].requirements").doesNotExist());
   // 绑定功能由另一完整挑战测试验证；此处仅建立项目可见性夹具。
   db.update("UPDATE software_order SET student_id=? WHERE id=?",student,UUID.fromString(order));
   mvc.perform(get("/api/v1/student/projects/"+project).header("Authorization","Bearer "+st)).andExpect(status().isOk()).andExpect(jsonPath("$.progress").value(0));
   mvc.perform(post("/api/v1/admin/orders/"+order+"/transition").header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"2\"").contentType("application/json").content("{\"action\":\"ACTIVATE\",\"reason\":\"测试缺少收款的拒绝\"}")).andExpect(status().isUnprocessableEntity());
   String milestones=path+"/"+project+"/milestones";var start=new java.util.concurrent.CountDownLatch(1);
   try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
    var tasks=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
    for(String token:List.of(adminToken,token2))tasks.add(pool.submit(()->{start.await();return mvc.perform(post(milestones).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(Map.of("name",UUID.randomUUID().toString(),"dueDate",date,"weight",60,"completed",true)))).andReturn().getResponse().getStatus();}));
    start.countDown();var statuses=new java.util.ArrayList<Integer>();for(var f:tasks)statuses.add(f.get());java.util.Collections.sort(statuses);assertEquals(List.of(201,422),statuses);
   }
   mvc.perform(get(path+"/"+project).header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.progress").value(60)).andExpect(jsonPath("$.version").value(2));
   mvc.perform(put(path+"/"+project).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isPreconditionFailed());
   var list=mvc.perform(get(milestones).header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andReturn().getResponse();String mid=json.readTree(list.getContentAsString()).get("items").get(0).get("id").asText();
   mvc.perform(delete("/api/v1/admin/milestones/"+mid).header("Authorization","Bearer "+adminToken).header("Idempotency-Key",UUID.randomUUID()).header("If-Match","\"1\"")).andExpect(status().isNoContent());
   mvc.perform(get("/api/v1/student/projects/"+project).header("Authorization","Bearer "+st)).andExpect(status().isOk()).andExpect(jsonPath("$.progress").value(0));
  } finally {db.update("DELETE FROM user_role WHERE user_id IN (?,?) AND role_id=?",adminId,admin2,superRole);}
 }
}
