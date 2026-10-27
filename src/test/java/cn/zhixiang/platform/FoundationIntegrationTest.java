package cn.zhixiang.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.zhixiang.platform.identity.security.TokenDigester;

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

import java.sql.*;
import java.util.*;

/** 真实 PostgreSQL 的 HTTP 纵向测试；每次运行独立 schema，测试结束仅删除自身 schema。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "ZX_INTEGRATION", matches = "true")
class FoundationIntegrationTest {
    static final String SCHEMA = "zx_test_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired MockMvc mvc;
    @Autowired cn.zhixiang.platform.probing.service.ConfigurationProbeService probes;
    @Autowired cn.zhixiang.platform.probing.mapper.ProbeMapper probeMapper;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    int httpPort;

    @Autowired cn.zhixiang.platform.storage.port.ObjectStorage storage;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlers;

    @Autowired JdbcTemplate db;
    @Autowired TokenDigester digester;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    cn.zhixiang.platform.identity.port.SmsGateway sms;

    @Autowired JsonMapper json;
    @Autowired cn.zhixiang.platform.notification.service.NotificationService notifications;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired cn.zhixiang.platform.asset.service.AssetValidation validation;
    @Autowired cn.zhixiang.platform.asset.mapper.AssetTaskMapper assetTasks;
    @Autowired cn.zhixiang.platform.knowledge.service.KnowledgeIndexer knowledgeIndexer;
    @Autowired cn.zhixiang.platform.knowledge.mapper.IndexMapper indexes;
    @Autowired cn.zhixiang.platform.operations.service.ActivityInbox activityInbox;
    @Autowired cn.zhixiang.platform.exporting.service.ExportService exports;
    @Autowired cn.zhixiang.platform.exporting.service.ExportCleanup exportCleanup;
    @Autowired cn.zhixiang.platform.exporting.mapper.ExportMapper exportMapper;
    @Autowired cn.zhixiang.platform.billing.service.QuotaService quotas;
    @Autowired cn.zhixiang.platform.chat.service.RunJournal journal;
    @Autowired cn.zhixiang.platform.chat.service.RunLeaseService runLeases;
    @Autowired cn.zhixiang.platform.chat.service.MessageService messages;
    @Autowired cn.zhixiang.platform.chat.service.RunStreamAccess streamAccess;
    @Autowired cn.zhixiang.platform.chat.service.RunCompletionService completion;
    @Autowired cn.zhixiang.platform.chat.service.RunExecutor runExecutor;
    @Autowired cn.zhixiang.platform.chat.service.RunControlService runControl;
    @Autowired cn.zhixiang.platform.billing.service.AdjustmentService adjustments;
    @Autowired cn.zhixiang.platform.billing.service.ReconciliationService reconciliations;
    @Autowired cn.zhixiang.platform.identity.service.SecretVault modelSecrets;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    cn.zhixiang.platform.chat.port.ModelGateway modelGateway;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    cn.zhixiang.platform.storage.port.ContentScanner scanner;

    static final UUID UPLOAD_STORE = UUID.randomUUID();
    @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;
    @Autowired cn.zhixiang.platform.operations.mapper.OutboxMapper outbox;
    private String adminToken, partnerToken;
    private UUID adminId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        try (var c = connection();
                var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + SCHEMA + " AUTHORIZATION zhixiang");
        }
        String url =
                System.getenv("ZX_DB_URL")
                        .replaceAll("([?&])currentSchema=[^&]+", "$1currentSchema=" + SCHEMA);
        if (!url.contains("currentSchema="))
            url += (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        String jdbc = url;
        r.add("ZX_UPLOAD_STORAGE_ID", () -> UPLOAD_STORE.toString());
        r.add("spring.datasource.url", () -> jdbc);
        r.add("spring.flyway.enabled", () -> true);
        r.add("spring.flyway.default-schema", () -> SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA);
    }

    static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                System.getenv("ZX_DB_URL"), "postgres", System.getenv("ZX_TEST_ADMIN_PASSWORD"));
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (var c = connection();
                var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void seedIdentity() {
        // 仅在隔离测试库种入会话，不提供生产跳过登录的接口或固定密码。
        adminId = UUID.randomUUID();
        adminToken = UUID.randomUUID().toString();
        partnerToken = UUID.randomUUID().toString();
        seed(adminId, "admin", adminToken);
        seed(UUID.randomUUID(), "partner", partnerToken);
        UUID role = UUID.randomUUID();
        db.update(
                "INSERT INTO role(id,code,name,platform,data_scope,status)"
                        + " VALUES(?,?,?,'admin','ALL','ENABLED')",
                role,
                role.toString(),
                "测试管理员");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, role);
        for (String p :
                List.of(
                        "schools:read",
                        "schools:write",
                        "schools:delete",
                        "majors:read",
                        "majors:write",
                        "majors:delete"))
            db.update("INSERT INTO role_permission VALUES(?,?)", role, p);
    }

    void seed(UUID id, String platform, String token) {
        db.update(
                "INSERT INTO"
                    + " user_account(id,platform,display_name,phone_digest,phone_masked,password_hash,status)"
                    + " VALUES(?,?,?,?,?,?,'ENABLED')",
                id,
                platform,
                "隔离测试",
                UUID.randomUUID().toString(),
                "138****0000",
                "not-a-login-password");
        db.update(
                "INSERT INTO"
                    + " auth_session(id,user_id,access_digest,refresh_digest,csrf_digest,auth_version,expires_at,refresh_expires_at,family_id)"
                    + " VALUES(?,?,?,?,?,1,now()+interval '15 minutes',now()+interval '7 days',?)",
                UUID.randomUUID(),
                id,
                digester.digest("access", token),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID());
    }

    @Autowired cn.zhixiang.platform.callback.mapper.OssCallbackMapper callbackReceipts;
    @Autowired cn.zhixiang.platform.asset.mapper.AssetMapper callbackAssets;
    @Autowired cn.zhixiang.platform.configuration.mapper.StorageMapper callbackStores;
    @Autowired cn.zhixiang.platform.shared.mapper.WriteMapper callbackWrites;

    @Autowired cn.zhixiang.platform.asset.service.AssetGarbageCollector assetGc;
    @Autowired cn.zhixiang.platform.asset.mapper.AssetGcMapper gcTasks;

    @Autowired
    cn.zhixiang.platform.notification.service.NotificationRetentionService notificationRetention;

    @Autowired cn.zhixiang.platform.operations.service.DeadLetterRecovery deadRecovery;

    @Test
    void explicitDeadLetterRecoveryPreservesOtherMessagesAndDeduplicates() throws Exception {
        UUID event = UUID.randomUUID(), aggregate = UUID.randomUUID();
        String type = "test.recovery." + UUID.randomUUID();
        db.update(
                "INSERT INTO outbox_event(id,aggregate_id,event_type,payload)"
                        + " VALUES(?,?,?,'{}'::jsonb)",
                event,
                aggregate,
                type);
        var props = new org.springframework.amqp.core.MessageProperties();
        props.setMessageId(event.toString());
        var message =
                new org.springframework.amqp.core.Message(
                        json.writeValueAsBytes(
                                Map.of(
                                        "eventId",
                                        event,
                                        "aggregateId",
                                        aggregate,
                                        "type",
                                        type,
                                        "data",
                                        Map.of())),
                        props);
        String queue = "zx.test.recovery." + UUID.randomUUID();
        var admin =
                new org.springframework.amqp.rabbit.core.RabbitAdmin(rabbit.getConnectionFactory());
        try {
            admin.declareQueue(new org.springframework.amqp.core.Queue(queue, false));
            rabbit.send(
                    "",
                    queue,
                    new org.springframework.amqp.core.Message(
                            "unrelated".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            new org.springframework.amqp.core.MessageProperties()));
            rabbit.send("", queue, message);
            assertThrows(
                    RuntimeException.class,
                    () -> deadRecovery.recover(queue, event, adminToken, "恢复测试"));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM inbox_event WHERE event_id=?",
                            Integer.class,
                            event));
            db.update(
                    "INSERT INTO role_permission(role_id,permission_code) SELECT"
                            + " role_id,'post_admin_jobs_id_retry' FROM user_role WHERE user_id=?",
                    adminId);
            assertTrue(deadRecovery.recover(queue, event, adminToken, "恢复测试"));
            rabbit.send("", queue, message);
            assertTrue(deadRecovery.recover(queue, event, adminToken, "确认重放去重"));
            assertEquals(
                    1L,
                    db.queryForObject(
                            "SELECT event_count FROM event_activity_day WHERE event_type=?",
                            Long.class,
                            type));
            assertFalse(deadRecovery.recover(queue, event, adminToken, "不存在事件"));
            assertEquals(
                    "unrelated",
                    new String(
                            rabbit.receive(queue, 1000).getBody(),
                            java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(
                    2,
                    db.queryForObject(
                            "SELECT count(*) FROM audit_event WHERE resource_id=? AND"
                                    + " action='DEAD_LETTER_REPLAY'",
                            Integer.class,
                            event));
        } finally {
            admin.deleteQueue(queue);
            db.update("DELETE FROM audit_event WHERE resource_id=?", event);
            db.update("DELETE FROM inbox_event WHERE event_id=?", event);
            db.update("DELETE FROM event_activity_day WHERE event_type=?", type);
            db.update("DELETE FROM outbox_event WHERE id=?", event);
        }
    }

    @Test
    void twoApiInstancesShareSessionIdempotencyAndRevocation() throws Exception {
        // 第二个独立Spring容器使用相同隔离schema，不能误连正式app数据。
        String url =
                System.getenv("ZX_DB_URL")
                        .replaceAll("([?&])currentSchema=[^&]+", "$1currentSchema=" + SCHEMA);
        if (!url.contains("currentSchema="))
            url += (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        var app = new org.springframework.boot.SpringApplication(PlatformApplication.class);
        app.setRegisterShutdownHook(false);
        var client = java.net.http.HttpClient.newHttpClient();
        var firstUri =
                java.net.URI.create("http://127.0.0.1:" + httpPort + "/api/v1/admin/schools");
        String key = UUID.randomUUID().toString();
        String body =
                json.writeValueAsString(
                        Map.of(
                                "name",
                                "双实例学院",
                                "code",
                                UUID.randomUUID().toString(),
                                "region",
                                "浙江",
                                "status",
                                "ENABLED"));
        try (var second =
                app.run(
                        "--server.port=0",
                        "--spring.profiles.active=api",
                        "--spring.datasource.url=" + url,
                        "--spring.flyway.enabled=false",
                        "--spring.datasource.hikari.maximum-pool-size=2")) {
            int port =
                    ((org.springframework.boot.web.server.context.WebServerApplicationContext)
                                    second)
                            .getWebServer()
                            .getPort();
            var secondUri =
                    java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/admin/schools");
            var requestA =
                    java.net.http.HttpRequest.newBuilder(firstUri)
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", key)
                            .header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                            .build();
            var requestB =
                    java.net.http.HttpRequest.newBuilder(secondUri)
                            .header("Authorization", "Bearer " + adminToken)
                            .header("Idempotency-Key", key)
                            .header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                            .build();
            var a = client.sendAsync(requestA, java.net.http.HttpResponse.BodyHandlers.ofString());
            var b = client.sendAsync(requestB, java.net.http.HttpResponse.BodyHandlers.ofString());
            var ra = a.get(15, java.util.concurrent.TimeUnit.SECONDS);
            var rb = b.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(201, ra.statusCode());
            assertEquals(201, rb.statusCode());
            assertEquals(ra.body(), rb.body());
            db.update("UPDATE user_account SET auth_version=auth_version+1 WHERE id=?", adminId);
            for (var uri : List.of(firstUri, secondUri)) {
                assertEquals(
                        401,
                        client.send(
                                        java.net.http.HttpRequest.newBuilder(uri)
                                                .header("Authorization", "Bearer " + adminToken)
                                                .GET()
                                                .build(),
                                        java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode());
            }
        }
        assertEquals(
                200,
                client.send(
                                java.net.http.HttpRequest.newBuilder(
                                                java.net.URI.create(
                                                        "http://127.0.0.1:"
                                                                + httpPort
                                                                + "/actuator/health/liveness"))
                                        .GET()
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.discarding())
                        .statusCode());
    }

    @Test
    void notificationRetentionKeepsReplayTombstonesAndPendingContent() {
        UUID event = UUID.randomUUID(), pending = UUID.randomUUID();
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> notifications.enqueue(adminId, event, "保留测试", "原始正文"));
        for (int i = 0;
                i < 100
                        && db.queryForObject(
                                        "SELECT count(*) FROM in_app_notification WHERE event_id=?",
                                        Integer.class,
                                        event)
                                == 0;
                i++) {
            assertTrue(notifications.processOne());
        }
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM in_app_notification WHERE event_id=?",
                        Integer.class,
                        event));
        tx.executeWithoutResult(s -> notifications.enqueue(adminId, pending, "待处理", "必须保留"));
        db.update(
                "UPDATE in_app_notification SET created_at=now()-interval '40 days' WHERE"
                        + " event_id=?",
                event);
        db.update(
                "UPDATE notification_delivery_task SET created_at=now()-interval '40"
                    + " days',finished_at=CASE WHEN state='DELIVERED' THEN now()-interval '40 days'"
                    + " END WHERE event_id IN (?,?)",
                event,
                pending);
        assertEquals(0, notificationRetention.runBatch(0));
        assertThrows(IllegalArgumentException.class, () -> notificationRetention.runBatch(1));
        assertEquals(2, notificationRetention.runBatch(30));
        assertEquals(0, notificationRetention.runBatch(30));
        assertEquals(
                "必须保留",
                db.queryForObject(
                        "SELECT body FROM notification_delivery_task WHERE event_id=?",
                        String.class,
                        pending));
        tx.executeWithoutResult(s -> notifications.enqueue(adminId, event, "保留测试", "原始正文"));
        tx.executeWithoutResult(s -> notifications.deliver(adminId, event, "保留测试", "原始正文"));
        assertEquals(
                "历史通知正文已清理",
                db.queryForObject(
                        "SELECT body FROM in_app_notification WHERE event_id=?",
                        String.class,
                        event));
        assertThrows(
                cn.zhixiang.platform.shared.exception.BusinessException.class,
                () ->
                        tx.executeWithoutResult(
                                s -> notifications.deliver(adminId, event, "保留测试", "篡改正文")));
        db.update("DELETE FROM in_app_notification WHERE event_id=?", event);
        db.update("DELETE FROM notification_delivery_task WHERE event_id IN (?,?)", event, pending);
    }

    @Test
    void requestTracePersistsInAuditAndMatchesUnauthorizedResponse() throws Exception {
        UUID audit = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        org.slf4j.MDC.put("requestId", trace);
        try {
            callbackWrites.auditReason(audit, adminId, "TRACE_TEST", "test", audit, "关联验证");
            assertEquals(
                    trace,
                    db.queryForObject(
                            "SELECT trace_id FROM audit_event WHERE id=?", String.class, audit));
        } finally {
            org.slf4j.MDC.remove("requestId");
            db.update("DELETE FROM audit_event WHERE id=?", audit);
        }
        var response =
                mvc.perform(get("/api/v1/admin/users").header("X-Request-ID", "forged"))
                        .andExpect(status().isUnauthorized())
                        .andReturn()
                        .getResponse();
        String header = response.getHeader("X-Request-ID");
        assertNotNull(UUID.fromString(header));
        assertEquals(header, json.readTree(response.getContentAsString()).get("traceId").asText());
        assertNull(org.slf4j.MDC.get("requestId"));
    }

    @Test
    void controlledNotificationTemplateSnapshotsAndSuppression() throws Exception {
        UUID template = UUID.randomUUID(),
                event = UUID.randomUUID(),
                disabled = UUID.randomUUID(),
                unsupported = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " notification_template(id,name,code,channel,provider_template_id,status,created_by,updated_by)"
                    + " VALUES(?,'交付通知','DELIVERY_PUBLISHED','IN_APP','delivery-published-brief-v1','ENABLED',?,?)",
                template,
                adminId,
                adminId);
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> notifications.enqueueDelivery(adminId, event));
        assertEquals(
                1L,
                db.queryForObject(
                        "SELECT template_version FROM notification_delivery_task WHERE event_id=?",
                        Long.class,
                        event));
        db.update(
                "UPDATE notification_template SET"
                        + " provider_template_id='delivery-published-v1',version=2 WHERE id=?",
                template);
        tx.executeWithoutResult(s -> notifications.enqueueDelivery(adminId, event));
        assertTrue(notifications.processOne());
        assertEquals(
                "新资料已发布，请查看项目。",
                db.queryForObject(
                        "SELECT body FROM in_app_notification WHERE event_id=?",
                        String.class,
                        event));
        db.update(
                "UPDATE notification_template SET status='DISABLED',version=3 WHERE id=?",
                template);
        tx.executeWithoutResult(s -> notifications.enqueueDelivery(adminId, disabled));
        assertEquals(
                "TEMPLATE_DISABLED",
                db.queryForObject(
                        "SELECT error_code FROM notification_delivery_task WHERE event_id=?",
                        String.class,
                        disabled));
        db.update(
                "UPDATE notification_template SET"
                        + " status='ENABLED',provider_template_id='unregistered-renderer',version=4"
                        + " WHERE id=?",
                template);
        tx.executeWithoutResult(s -> notifications.enqueueDelivery(adminId, unsupported));
        assertEquals(
                "TEMPLATE_RENDERER_UNAVAILABLE",
                db.queryForObject(
                        "SELECT error_code FROM notification_delivery_task WHERE event_id=?",
                        String.class,
                        unsupported));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM in_app_notification WHERE event_id IN (?,?)",
                        Integer.class,
                        disabled,
                        unsupported));
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> db.update("DELETE FROM notification_template WHERE id=?", template));
        db.update("DELETE FROM in_app_notification WHERE event_id=?", event);
        db.update("DELETE FROM notification_delivery_task WHERE template_id=?", template);
        db.update("DELETE FROM notification_template WHERE id=?", template);
    }

    @Test
    void garbageCollectionOnlyRemovesRetiredExactKeys() throws Exception {
        UUID board = UUID.randomUUID(),
                postId = UUID.randomUUID(),
                asset = UUID.randomUUID(),
                upload = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " forum_board(id,name,code,requires_review,sort_order,status,created_by,updated_by)"
                    + " VALUES(?,'回收隔离',?,true,0,'ENABLED',?,?)",
                board,
                board.toString(),
                adminId,
                adminId);
        db.update(
                "INSERT INTO forum_post(id,board_id,author_id,title,body,state)"
                        + " VALUES(?,?,?,'回收测试','隔离内容','PENDING')",
                postId,
                board,
                adminId);
        db.update(
                "INSERT INTO"
                    + " storage_config(id,name,provider,bucket,region,endpoint,credential_ref,access,status,created_by,updated_by)"
                    + " VALUES(?,'隔离上传','S3_COMPATIBLE','zx-quarantine-private',?,?,'local-minio','PRIVATE','ENABLED',?,?)"
                    + " ON CONFLICT(id) DO NOTHING",
                UPLOAD_STORE,
                System.getenv("ZX_S3_REGION"),
                System.getenv("ZX_S3_ENDPOINT"),
                adminId,
                adminId);
        String key = "quarantine/" + asset + "/" + upload,
                neighbor = key + "-keep",
                attempt = "verified/" + asset + "/orphan";
        var target =
                new cn.zhixiang.platform.storage.port.ObjectStorage.Target(
                        "S3_COMPATIBLE",
                        System.getenv("ZX_S3_ENDPOINT"),
                        System.getenv("ZX_S3_REGION"),
                        "zx-quarantine-private",
                        "local-minio");
        storage.write(target, key, new byte[] {1, 2, 3}, "application/octet-stream");
        storage.write(target, neighbor, new byte[] {1, 2, 3}, "application/octet-stream");
        db.update(
                "INSERT INTO"
                    + " asset(id,upload_id,owner_type,forum_post_id,storage_id,filename,size,sha256,mime_type,upload_key,upload_expires_at,created_by,updated_by)"
                    + " VALUES(?,?,'FORUM',?,?,'gc.txt',3,?,'application/octet-stream',?,now()-interval"
                    + " '2 days',?,?)",
                asset,
                upload,
                postId,
                UPLOAD_STORE,
                "a".repeat(64),
                key,
                adminId,
                adminId);
        try {
            assertTrue(assetGc.runOne());
            assertThrows(
                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                    () -> storage.stat(target, key));
            assertEquals(3, storage.stat(target, neighbor).size());
            assertNotNull(
                    db.queryForObject(
                            "SELECT completed_at FROM asset_object_gc WHERE asset_id=? AND"
                                    + " kind='UPLOAD'",
                            java.sql.Timestamp.class,
                            asset));
            storage.write(target, attempt, new byte[] {1, 2, 3}, "application/octet-stream");
            gcTasks.track(asset, UPLOAD_STORE, attempt, 1);
            db.update(
                    "INSERT INTO asset_validation_task(asset_id,state,generation,lease_until)"
                            + " VALUES(?,'RUNNING',1,now()+interval '5 minutes')",
                    asset);
            db.update(
                    "UPDATE asset_object_gc SET next_attempt_at=now()-interval '1 second' WHERE"
                            + " asset_id=? AND kind='ATTEMPT'",
                    asset);
            assertTrue(assetGc.runOne());
            assertEquals(3, storage.stat(target, attempt).size());
            assertEquals(
                    "GC_NOT_RETIRED",
                    db.queryForObject(
                            "SELECT error_code FROM asset_object_gc WHERE asset_id=? AND"
                                    + " kind='ATTEMPT'",
                            String.class,
                            asset));
            db.update(
                    "UPDATE asset_validation_task SET state='FAILED',lease_until=NULL WHERE"
                            + " asset_id=?",
                    asset);
            db.update(
                    "UPDATE asset_object_gc SET next_attempt_at=now()-interval '1 second' WHERE"
                            + " asset_id=? AND kind='ATTEMPT'",
                    asset);
            assertTrue(assetGc.runOne());
            assertThrows(
                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                    () -> storage.stat(target, attempt));
            storage.write(target, attempt, new byte[] {4, 5, 6}, "application/octet-stream");
            db.update(
                    "UPDATE asset_object_gc SET next_attempt_at=now()-interval '1 second' WHERE"
                            + " asset_id=? AND kind='ATTEMPT'",
                    asset);
            assertTrue(assetGc.runOne());
            assertThrows(
                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                    () -> storage.stat(target, attempt));
            assertEquals(3, storage.stat(target, neighbor).size());
        } finally {
            storage.purge(target, key);
            storage.purge(target, neighbor);
            storage.purge(target, attempt);
            db.update("DELETE FROM asset_object_gc WHERE asset_id=?", asset);
            db.update("DELETE FROM asset_validation_task WHERE asset_id=?", asset);
            db.update("DELETE FROM asset WHERE id=?", asset);
            db.update("DELETE FROM forum_post WHERE id=?", postId);
            db.update("DELETE FROM forum_board WHERE id=?", board);
        }
    }

    @Test
    void ossCallbackUsesRealTransactionAndKeepsQuarantine() throws Exception {
        mvc.perform(
                        post("/api/v1/callbacks/oss")
                                .contentType("application/x-www-form-urlencoded")
                                .content("size=3"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OSS_CALLBACK_UNCONFIGURED"));
        mvc.perform(
                        post("/api/v1/callbacks/oss")
                                .contentType("application/x-www-form-urlencoded")
                                .content("x".repeat(16385)))
                .andExpect(status().isPayloadTooLarge());
        UUID board = UUID.randomUUID(),
                postId = UUID.randomUUID(),
                store = UUID.randomUUID(),
                asset = UUID.randomUUID(),
                upload = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " forum_board(id,name,code,requires_review,sort_order,status,created_by,updated_by)"
                    + " VALUES(?,'回调隔离',?,true,0,'ENABLED',?,?)",
                board,
                board.toString(),
                adminId,
                adminId);
        db.update(
                "INSERT INTO forum_post(id,board_id,author_id,title,body,state)"
                        + " VALUES(?,?,?,'回调测试','隔离内容','PENDING')",
                postId,
                board,
                adminId);
        db.update(
                "INSERT INTO"
                    + " storage_config(id,name,provider,bucket,region,endpoint,credential_ref,access,status,created_by,updated_by)"
                    + " VALUES(?,'回调隔离','ALIYUN_OSS',?,'test','https://oss.example.test','test-only','PRIVATE','ENABLED',?,?)",
                store,
                store.toString(),
                adminId,
                adminId);
        db.update(
                "INSERT INTO"
                    + " asset(id,upload_id,owner_type,forum_post_id,storage_id,filename,size,sha256,mime_type,upload_key,upload_expires_at,created_by,updated_by)"
                    + " VALUES(?,?,'FORUM',?,?,'test.txt',3,?,'text/plain',?,now()+interval '5"
                    + " minutes',?,?)",
                asset,
                upload,
                postId,
                store,
                "a".repeat(64),
                "quarantine/" + asset,
                adminId,
                adminId);
        try {
            var signature =
                    org.mockito.Mockito.mock(
                            cn.zhixiang.platform.callback.security.OssCallbackSignature.class);
            var external =
                    org.mockito.Mockito.mock(cn.zhixiang.platform.storage.port.ObjectStorage.class);
            org.mockito.Mockito.when(
                            external.stat(
                                    org.mockito.ArgumentMatchers.any(),
                                    org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(
                            new cn.zhixiang.platform.storage.port.ObjectStorage.Metadata(
                                    3, "etag", "version-1", "text/plain"));
            var callbacks =
                    new cn.zhixiang.platform.callback.service.OssCallbackService(
                            signature,
                            callbackReceipts,
                            callbackAssets,
                            callbackStores,
                            external,
                            callbackWrites,
                            txManager);
            byte[] body =
                    ("uploadId="
                                    + upload
                                    + "&bucket="
                                    + store
                                    + "&object=quarantine%2F"
                                    + asset
                                    + "&size=3&etag=etag")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(
                    "QUARANTINED", callbacks.receive(body, "fixture", "fixture", null).state());
            assertEquals(
                    "QUARANTINED", callbacks.receive(body, "fixture", "fixture", null).state());
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM oss_callback_receipt WHERE asset_id=?",
                            Integer.class,
                            asset));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM asset_validation_task WHERE asset_id=?",
                            Integer.class,
                            asset));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM audit_event WHERE resource_id=? AND"
                                    + " action='OSS_CALLBACK_VERIFIED'",
                            Integer.class,
                            asset));
        } finally {
            db.update("DELETE FROM oss_callback_receipt WHERE asset_id=?", asset);
            db.update("DELETE FROM asset_validation_task WHERE asset_id=?", asset);
            db.update("DELETE FROM asset WHERE id=?", asset);
            db.update("DELETE FROM forum_post WHERE id=?", postId);
            db.update("DELETE FROM forum_board WHERE id=?", board);
            db.update("DELETE FROM storage_config WHERE id=?", store);
        }
    }

    @Test
    void configurationProbeVersionLeaseAndRealStorage() throws Exception {
        UUID role =
                db.queryForObject(
                        "SELECT role_id FROM user_role WHERE user_id=?", UUID.class, adminId);
        for (String permission :
                List.of("post_admin_storages_id_test", "post_admin_domains_id_test"))
            db.update("INSERT INTO role_permission VALUES(?,?)", role, permission);
        UUID store = UPLOAD_STORE;
        db.update(
                "INSERT INTO"
                    + " storage_config(id,name,provider,bucket,region,endpoint,credential_ref,access,status,created_by,updated_by)"
                    + " VALUES(?,'探测隔离','S3_COMPATIBLE','zx-quarantine-private',?,?,'local-minio','PRIVATE','ENABLED',?,?)"
                    + " ON CONFLICT(id) DO NOTHING",
                store,
                System.getenv("ZX_S3_REGION"),
                System.getenv("ZX_S3_ENDPOINT"),
                adminId,
                adminId);
        String path = "/api/v1/admin/storages/" + store + "/test",
                body = "{\"reason\":\"隔离验证\"}",
                key = UUID.randomUUID().toString();
        String id =
                json.readTree(
                                mvc.perform(
                                                post(path)
                                                        .header(
                                                                "Authorization",
                                                                "Bearer " + adminToken)
                                                        .header("Idempotency-Key", key)
                                                        .contentType("application/json")
                                                        .content(body))
                                        .andExpect(status().isAccepted())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("id")
                        .asText();
        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", key)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id));
        assertTrue(probes.runOne());
        assertEquals(
                "SUCCEEDED",
                db.queryForObject(
                        "SELECT state FROM configuration_probe_job WHERE id=?",
                        String.class,
                        UUID.fromString(id)),
                db.queryForObject(
                        "SELECT error_code FROM configuration_probe_job WHERE id=?",
                        String.class,
                        UUID.fromString(id)));
        assertFalse(probes.runOne());
        String changed =
                json.readTree(
                                mvc.perform(
                                                post(path)
                                                        .header(
                                                                "Authorization",
                                                                "Bearer " + adminToken)
                                                        .header(
                                                                "Idempotency-Key",
                                                                UUID.randomUUID())
                                                        .contentType("application/json")
                                                        .content(body))
                                        .andExpect(status().isAccepted())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("id")
                        .asText();
        db.update("UPDATE storage_config SET version=version+1 WHERE id=?", store);
        assertTrue(probes.runOne());
        assertEquals(
                "PROBE_CONFIG_CHANGED",
                db.queryForObject(
                        "SELECT error_code FROM configuration_probe_job WHERE id=?",
                        String.class,
                        UUID.fromString(changed)));
        String revoked =
                json.readTree(
                                mvc.perform(
                                                post(path)
                                                        .header(
                                                                "Authorization",
                                                                "Bearer " + adminToken)
                                                        .header(
                                                                "Idempotency-Key",
                                                                UUID.randomUUID())
                                                        .contentType("application/json")
                                                        .content(body))
                                        .andExpect(status().isAccepted())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("id")
                        .asText();
        db.update(
                "DELETE FROM role_permission WHERE role_id=? AND"
                        + " permission_code='post_admin_storages_id_test'",
                role);
        assertTrue(probes.runOne());
        assertEquals(
                "PROBE_PERMISSION_REVOKED",
                db.queryForObject(
                        "SELECT error_code FROM configuration_probe_job WHERE id=?",
                        String.class,
                        UUID.fromString(revoked)));
        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer " + partnerToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden());
        db.update("INSERT INTO role_permission VALUES(?,'post_admin_storages_id_test')", role);
        String recovered =
                json.readTree(
                                mvc.perform(
                                                post(path)
                                                        .header(
                                                                "Authorization",
                                                                "Bearer " + adminToken)
                                                        .header(
                                                                "Idempotency-Key",
                                                                UUID.randomUUID())
                                                        .contentType("application/json")
                                                        .content(body))
                                        .andExpect(status().isAccepted())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("id")
                        .asText();
        db.update(
                "UPDATE configuration_probe_job SET"
                        + " state='RUNNING',generation=1,attempts=1,lease_until=now()-interval '1"
                        + " second' WHERE id=?",
                UUID.fromString(recovered));
        assertTrue(probes.runOne());
        assertEquals(0, probeMapper.finish(UUID.fromString(recovered), 1, "LATE_FAILURE"));
        assertEquals(
                "SUCCEEDED",
                db.queryForObject(
                        "SELECT state FROM configuration_probe_job WHERE id=?",
                        String.class,
                        UUID.fromString(recovered)));
    }

    @Test
    void forumBoardCrudAndStudentVisibility() throws Exception {
        UUID role =
                db.queryForObject(
                        "SELECT role_id FROM user_role WHERE user_id=?", UUID.class, adminId);
        for (String permission :
                List.of("forum-boards:read", "forum-boards:write", "forum-boards:delete"))
            db.update("INSERT INTO role_permission VALUES(?,?)", role, permission);
        String studentToken = UUID.randomUUID().toString();
        UUID boardStudent = UUID.randomUUID();
        seed(boardStudent, "student", studentToken);
        db.update(
                "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')",
                boardStudent);
        String path = "/api/v1/admin/forum-boards",
                code = UUID.randomUUID().toString(),
                key = UUID.randomUUID().toString();
        String body =
                json.writeValueAsString(
                        Map.of(
                                "name",
                                "论坛_字面%",
                                "code",
                                code,
                                "requiresReview",
                                true,
                                "sortOrder",
                                4,
                                "status",
                                "ENABLED"));
        var response =
                mvc.perform(
                                post(path)
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", key)
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.requiresReview").value(true))
                        .andReturn()
                        .getResponse();
        String id = json.readTree(response.getContentAsString()).get("id").asString();
        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", key)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(content().json(response.getContentAsString()));
        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isConflict());
        mvc.perform(
                        get(path)
                                .header("Authorization", "Bearer " + adminToken)
                                .param("q", code)
                                .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(
                        get(path)
                                .header("Authorization", "Bearer " + adminToken)
                                .param("sort", "name;DROP TABLE forum_board"))
                .andExpect(status().isBadRequest());
        String dict = "/api/v1/student/dictionaries/forum-boards";
        mvc.perform(get(dict).header("Authorization", "Bearer " + studentToken).param("q", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(dict).header("Authorization", "Bearer " + partnerToken))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put(path + "/" + id)
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .header("If-Match", "\"99\"")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(
                        put(path + "/" + id)
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .header("If-Match", "\"1\"")
                                .contentType("application/json")
                                .content(body.replace("true", "false")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresReview").value(false))
                .andExpect(header().string("ETag", "\"2\""));
        mvc.perform(
                        post(path + "/" + id + "/status")
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .header("If-Match", "\"2\"")
                                .contentType("application/json")
                                .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(dict).header("Authorization", "Bearer " + studentToken).param("q", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(
                        get(dict)
                                .header("Authorization", "Bearer " + studentToken)
                                .param("status", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(
                        delete(path + "/" + id)
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .header("If-Match", "\"3\""))
                .andExpect(status().isNoContent());
        mvc.perform(get(path + "/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void notificationTemplateConfigurationDoesNotSend() throws Exception {
        UUID role =
                db.queryForObject(
                        "SELECT role_id FROM user_role WHERE user_id=?", UUID.class, adminId);
        for (String permission :
                List.of(
                        "notification-templates:read",
                        "notification-templates:write",
                        "notification-templates:delete"))
            db.update("INSERT INTO role_permission VALUES(?,?)", role, permission);
        String path = "/api/v1/admin/notification-templates",
                code = UUID.randomUUID().toString(),
                key = UUID.randomUUID().toString();
        String body =
                json.writeValueAsString(
                        Map.of(
                                "name",
                                "订单通知模板",
                                "code",
                                code,
                                "channel",
                                "SMS",
                                "providerTemplateId",
                                "isolated-test-template",
                                "status",
                                "DISABLED"));
        var created =
                mvc.perform(
                                post(path)
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", key)
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.channel").value("SMS"))
                        .andReturn()
                        .getResponse();
        String id = json.readTree(created.getContentAsString()).get("id").asString();
        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", key)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(content().json(created.getContentAsString()));
        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isConflict());
        mvc.perform(
                        get(path)
                                .header("Authorization", "Bearer " + adminToken)
                                .param("q", code)
                                .param("status", "DISABLED")
                                .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(path).header("Authorization", "Bearer " + partnerToken))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put(path + "/" + id)
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .header("If-Match", "\"1\"")
                                .contentType("application/json")
                                .content(body.replace("SMS", "IN_APP")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("IN_APP"));
        mvc.perform(
                        post(path + "/" + id + "/status")
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .header("If-Match", "\"1\"")
                                .contentType("application/json")
                                .content("{\"status\":\"ENABLED\"}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(
                        post(path + "/" + id + "/status")
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .header("If-Match", "\"2\"")
                                .contentType("application/json")
                                .content("{\"status\":\"ENABLED\"}"))
                .andExpect(status().isOk());
        mvc.perform(
                        delete(path + "/" + id)
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .header("If-Match", "\"3\""))
                .andExpect(status().isNoContent());
        mvc.perform(get(path + "/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void notificationsArePrivateDeduplicatedAndMonotonic() throws Exception {
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        UUID role =
                db.queryForObject(
                        "SELECT role_id FROM user_role WHERE user_id=?", UUID.class, adminId);
        for (String permission :
                List.of("get_admin_notifications", "post_admin_notifications_id_read"))
            db.update("INSERT INTO role_permission VALUES(?,?)", role, permission);
        UUID student = UUID.randomUUID(), partner = UUID.randomUUID();
        String st = UUID.randomUUID().toString(), pt = UUID.randomUUID().toString();
        seed(student, "student", st);
        seed(partner, "partner", pt);
        db.update(
                "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')", student);
        db.update(
                "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000003')", partner);
        UUID event = UUID.randomUUID();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        UUID notification;
        try {
            var gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<UUID> delivery =
                    () -> {
                        gate.await();
                        return tx.execute(
                                x -> notifications.deliver(student, event, "测试通知", "同事件仅投递一次"));
                    };
            var a = pool.submit(delivery);
            var b = pool.submit(delivery);
            gate.countDown();
            notification = a.get();
            assertEquals(notification, b.get());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM in_app_notification WHERE recipient_id=? AND"
                                + " event_id=?",
                        Integer.class,
                        student,
                        event));
        assertThrows(
                cn.zhixiang.platform.shared.exception.BusinessException.class,
                () -> tx.execute(x -> notifications.deliver(student, event, "改变文案", "同事件仅投递一次")));
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.execute(
                                x -> {
                                    notifications.deliver(
                                            partner, UUID.randomUUID(), "回滚测试", "不得投递");
                                    throw new IllegalStateException("rollback");
                                }));
        UUID queued = UUID.randomUUID();
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.execute(
                                x -> {
                                    notifications.enqueue(partner, queued, "回滚队列", "事务不可提前投递");
                                    throw new IllegalStateException("rollback");
                                }));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM notification_delivery_task WHERE event_id=?",
                        Integer.class,
                        queued));
        tx.executeWithoutResult(x -> notifications.enqueue(adminId, queued, "后台消息", "仅收件人可见"));
        tx.executeWithoutResult(x -> notifications.enqueue(adminId, queued, "后台消息", "仅收件人可见"));
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> notifications.processOne());
            var b = workers.submit(() -> notifications.processOne());
            a.get();
            b.get();
        } finally {
            workers.shutdownNow();
        }
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM in_app_notification WHERE recipient_id=? AND"
                                + " event_id=?",
                        Integer.class,
                        adminId,
                        queued));
        assertEquals(
                "DELIVERED",
                db.queryForObject(
                        "SELECT state FROM notification_delivery_task WHERE recipient_id=? AND"
                                + " event_id=?",
                        String.class,
                        adminId,
                        queued));
        UUID adminNotice =
                db.queryForObject(
                        "SELECT id FROM in_app_notification WHERE recipient_id=? AND event_id=?",
                        UUID.class,
                        adminId,
                        queued);
        for (var pair : Map.of("student", st, "partner", pt, "admin", adminToken).entrySet()) {
            String root = "/api/v1/" + pair.getKey() + "/notifications", token = pair.getValue();
            mvc.perform(get(root).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(pair.getKey().equals("partner") ? 0 : 1));
            if (!pair.getKey().equals("student"))
                mvc.perform(
                                post(root + "/" + notification + "/read")
                                        .header("Authorization", "Bearer " + token)
                                        .header("Idempotency-Key", UUID.randomUUID()))
                        .andExpect(status().isNotFound());
        }
        String path = "/api/v1/student/notifications",
                read = path + "/" + notification + "/read",
                key = UUID.randomUUID().toString();
        mvc.perform(get(path).header("Authorization", "Bearer " + st).param("status", "ENABLED"))
                .andExpect(status().isBadRequest());
        for (int i = 0; i < 3; i++)
            mvc.perform(
                            post(read)
                                    .header("Authorization", "Bearer " + st)
                                    .header(
                                            "Idempotency-Key",
                                            i == 2 ? UUID.randomUUID().toString() : key))
                    .andExpect(status().isNoContent());
        mvc.perform(get(path).header("Authorization", "Bearer " + st).param("status", "UNREAD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(path).header("Authorization", "Bearer " + st).param("status", "READ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].read").value(true));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE resource_id=? AND action='READ'",
                        Integer.class,
                        notification));
        mvc.perform(
                        post("/api/v1/student/notifications/" + adminNotice + "/read")
                                .header("Authorization", "Bearer " + st)
                                .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void forumPostReviewVisibilityAndOwnerRules() throws Exception {
        db.update(
                "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000001')", adminId);
        try {
            UUID author = UUID.randomUUID(),
                    other = UUID.randomUUID(),
                    board = UUID.randomUUID(),
                    tag = UUID.randomUUID();
            String st = UUID.randomUUID().toString(), ot = UUID.randomUUID().toString();
            seed(author, "student", st);
            seed(other, "student", ot);
            for (UUID u : List.of(author, other))
                db.update(
                        "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')",
                        u);
            db.update(
                    "INSERT INTO"
                        + " forum_board(id,name,code,requires_review,sort_order,status,created_by,updated_by)"
                        + " VALUES(?,'论坛测试',?,true,0,'ENABLED',?,?)",
                    board,
                    board.toString(),
                    adminId,
                    adminId);
            db.update(
                    "INSERT INTO knowledge_tag(id,name,code,status,created_by,updated_by)"
                            + " VALUES(?,'论坛标签',?,'ENABLED',?,?)",
                    tag,
                    tag.toString(),
                    adminId,
                    adminId);
            String path = "/api/v1/student/posts", key = UUID.randomUUID().toString();
            String body =
                    json.writeValueAsString(
                            Map.of(
                                    "boardId",
                                    board,
                                    "title",
                                    "帖子_字面%",
                                    "body",
                                    "隔离测试正文",
                                    "tagIds",
                                    List.of(tag),
                                    "assetIds",
                                    List.of()));
            var response =
                    mvc.perform(
                                    post(path)
                                            .header("Authorization", "Bearer " + st)
                                            .header("Idempotency-Key", key)
                                            .contentType("application/json")
                                            .content(body))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.state").value("PENDING"))
                            .andReturn()
                            .getResponse();
            String id = json.readTree(response.getContentAsString()).get("id").asString(),
                    item = path + "/" + id;
            mvc.perform(
                            post(path)
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", key)
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(content().json(response.getContentAsString()));
            mvc.perform(get(item).header("Authorization", "Bearer " + ot))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            get(path)
                                    .header("Authorization", "Bearer " + ot)
                                    .param("boardId", board.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            mvc.perform(
                            get(path)
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", "_字面%")
                                    .param("boardId", board.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
            mvc.perform(
                            get(path)
                                    .header("Authorization", "Bearer " + st)
                                    .param("status", "ENABLED"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get(path).header("Authorization", "Bearer " + partnerToken))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            delete("/api/v1/admin/forum-boards/" + board)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\""))
                    .andExpect(status().isConflict());
            mvc.perform(
                            delete("/api/v1/admin/tags/" + tag)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\""))
                    .andExpect(status().isConflict());
            UUID peer = UUID.randomUUID(), peerRole = UUID.randomUUID();
            String peerToken = UUID.randomUUID().toString();
            seed(peer, "admin", peerToken);
            db.update(
                    "INSERT INTO role(id,code,name,platform,data_scope,status)"
                            + " VALUES(?,?,?,'admin','ALL','ENABLED')",
                    peerRole,
                    peerRole.toString(),
                    "隔离审核员");
            db.update("INSERT INTO user_role VALUES(?,?)", peer, peerRole);
            db.update(
                    "INSERT INTO role_permission VALUES(?,'post_admin_posts_id_moderation')",
                    peerRole);
            var moderationPool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var gate = new java.util.concurrent.CountDownLatch(1);
                var a1 =
                        moderationPool.submit(
                                () -> {
                                    gate.await();
                                    return forumModeration(id, 1, "APPROVE")
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                });
                var a2 =
                        moderationPool.submit(
                                () -> {
                                    gate.await();
                                    return mvc.perform(
                                                    post("/api/v1/admin/posts/"
                                                                    + id
                                                                    + "/moderation")
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + peerToken)
                                                            .header(
                                                                    "Idempotency-Key",
                                                                    UUID.randomUUID())
                                                            .header("If-Match", "\"1\"")
                                                            .contentType("application/json")
                                                            .content(
                                                                    "{\"action\":\"APPROVE\",\"reason\":\"并发审核\"}"))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                });
                gate.countDown();
                assertEquals(Set.of(200, 412), Set.of(a1.get(), a2.get()));
            } finally {
                moderationPool.shutdownNow();
            }
            mvc.perform(get(item).header("Authorization", "Bearer " + ot))
                    .andExpect(status().isOk());
            mvc.perform(
                            put(item)
                                    .header("Authorization", "Bearer " + ot)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"2\"")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isNotFound());
            String comments = item + "/comments";
            String parent =
                    forumComment(comments, ot, "主评论", null)
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            String parentId = json.readTree(parent).get("id").asString();
            String reply =
                    forumComment(comments, st, "回复", parentId)
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            String replyId = json.readTree(reply).get("id").asString();
            forumComment(comments, st, "过深回复", replyId).andExpect(status().isUnprocessableEntity());
            forumComment(comments, st, "无效父级", UUID.randomUUID().toString())
                    .andExpect(status().isUnprocessableEntity());
            mvc.perform(get(comments).header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2));
            String moderation = "/api/v1/admin/comments/" + parentId + "/moderation";
            mvc.perform(
                            post(moderation)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content("{\"action\":\"HIDE\",\"reason\":\"隐藏测试\"}"))
                    .andExpect(status().isOk());
            mvc.perform(get(comments).header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            mvc.perform(
                            get("/api/v1/admin/posts/" + id + "/comments")
                                    .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2));
            mvc.perform(
                            post(moderation)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"2\"")
                                    .contentType("application/json")
                                    .content("{\"action\":\"APPROVE\",\"reason\":\"恢复测试\"}"))
                    .andExpect(status().isOk());
            mvc.perform(get(comments).header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2));
            mvc.perform(
                            delete("/api/v1/student/comments/" + parentId)
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"3\""))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            delete("/api/v1/student/comments/" + replyId)
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\""))
                    .andExpect(status().isNoContent());
            String reportBody = "{\"reason\":\"隔离举报测试\"}";
            var reportResponse =
                    mvc.perform(
                                    post(item + "/reports")
                                            .header("Authorization", "Bearer " + ot)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(reportBody))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            String reportId = json.readTree(reportResponse).get("id").asString();
            mvc.perform(
                            post(item + "/reports")
                                    .header("Authorization", "Bearer " + ot)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(reportBody))
                    .andExpect(status().isCreated())
                    .andExpect(content().json(reportResponse));
            mvc.perform(
                            post(item + "/reports")
                                    .header("Authorization", "Bearer " + ot)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content("{\"reason\":\"改变原因\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(get("/api/v1/admin/reports").header("Authorization", "Bearer " + ot))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get("/api/v1/admin/reports")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("q", "隔离举报测试"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
            mvc.perform(
                            post("/api/v1/admin/reports/" + reportId + "/decision")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content("{\"decision\":\"DISMISSED\",\"reason\":\"测试不成立\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("DISMISSED"));
            forumModeration(id, 2, "LOCK").andExpect(status().isOk());
            forumComment(comments, st, "锁定后评论", null).andExpect(status().isUnprocessableEntity());
            mvc.perform(
                            put(item)
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"3\"")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isUnprocessableEntity());
            forumModeration(id, 3, "UNLOCK").andExpect(status().isOk());
            mvc.perform(
                            put(item)
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"4\"")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("PENDING"));
            forumModeration(id, 5, "PIN").andExpect(status().isUnprocessableEntity());
            forumModeration(id, 5, "APPROVE").andExpect(status().isOk());
            forumModeration(id, 6, "PIN")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pinned").value(true));
            forumModeration(id, 7, "HIDE")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pinned").value(false));
            mvc.perform(get(item).header("Authorization", "Bearer " + st))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            get(path)
                                    .header("Authorization", "Bearer " + st)
                                    .param("boardId", board.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            mvc.perform(
                            get("/api/v1/admin/posts/" + id)
                                    .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
            forumModeration(id, 8, "APPROVE").andExpect(status().isOk());
            db.update("UPDATE forum_board SET status='DISABLED' WHERE id=?", board);
            mvc.perform(get(item).header("Authorization", "Bearer " + st))
                    .andExpect(status().isNotFound());
            db.update(
                    "UPDATE forum_board SET status='ENABLED',requires_review=false WHERE id=?",
                    board);
            mvc.perform(
                            delete(item)
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"9\""))
                    .andExpect(status().isNoContent());
            mvc.perform(
                            get("/api/v1/admin/posts/" + id)
                                    .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound());
            for (int n = 0; n < 4; n++)
                mvc.perform(
                                post(path)
                                        .header("Authorization", "Bearer " + st)
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.state").value("PUBLISHED"));
            mvc.perform(
                            post(path)
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isTooManyRequests());
        } finally {
            db.update(
                    "DELETE FROM user_role WHERE user_id=? AND"
                            + " role_id='00000000-0000-4000-8000-000000000001'",
                    adminId);
        }
    }

    org.springframework.test.web.servlet.ResultActions forumComment(
            String path, String token, String body, String parent) throws Exception {
        var data = new HashMap<String, Object>();
        data.put("body", body);
        data.put("parentId", parent);
        return mvc.perform(
                post(path)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json")
                        .content(json.writeValueAsString(data)));
    }

    org.springframework.test.web.servlet.ResultActions forumModeration(
            String id, long version, String action) throws Exception {
        return mvc.perform(
                post("/api/v1/admin/posts/" + id + "/moderation")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "\"" + version + "\"")
                        .contentType("application/json")
                        .content(
                                json.writeValueAsString(
                                        Map.of("action", action, "reason", "隔离审核测试"))));
    }

    @Test
    void publicProbeUsesRealDependencyGroup() throws Exception {
        mvc.perform(get("/api/v1/health/live"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/health/ready"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
    }

    @Test
    void disabledSchoolPreventsMajorEnable() throws Exception {
        String schoolBody =
                json.writeValueAsString(
                        Map.of(
                                "name",
                                "停用保护院校",
                                "code",
                                UUID.randomUUID().toString(),
                                "region",
                                "浙江",
                                "status",
                                "ENABLED"));
        var school =
                mvc.perform(
                                post("/api/v1/admin/schools")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .contentType("application/json")
                                        .content(schoolBody))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        String schoolId = json.readTree(school.getContentAsString()).get("id").asText();
        String majorBody =
                json.writeValueAsString(
                        Map.of(
                                "name",
                                "专业",
                                "code",
                                "TEST",
                                "schoolId",
                                schoolId,
                                "level",
                                "BACHELOR",
                                "status",
                                "DISABLED"));
        var major =
                mvc.perform(
                                post("/api/v1/admin/majors")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .contentType("application/json")
                                        .content(majorBody))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        String majorId = json.readTree(major.getContentAsString()).get("id").asText();
        mvc.perform(
                        post("/api/v1/admin/schools/" + schoolId + "/status")
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .header("If-Match", "\"1\"")
                                .contentType("application/json")
                                .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/v1/admin/majors/" + majorId + "/status")
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .header("If-Match", "\"1\"")
                                .contentType("application/json")
                                .content("{\"status\":\"ENABLED\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(
                        get("/api/v1/admin/majors")
                                .header("Authorization", "Bearer " + adminToken)
                                .param("schoolId", schoolId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void registerResetAndSingleUseChallenges() throws Exception {
        var codes = new java.util.concurrent.ConcurrentHashMap<UUID, String>();
        org.mockito.Mockito.doAnswer(
                        call -> {
                            codes.put(call.getArgument(0), call.getArgument(2));
                            return null;
                        })
                .when(sms)
                .send(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        String phone = "13612345678", password = "注册长密码".repeat(15);
        var request =
                post("/api/v1/auth/challenges")
                        .contentType("application/json")
                        .content(
                                json.writeValueAsString(
                                        Map.of("phone", phone, "purpose", "REGISTER")));
        String challengeResponse =
                mvc.perform(request)
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID challenge =
                UUID.fromString(json.readTree(challengeResponse).get("challengeId").asText());
        String registration =
                json.writeValueAsString(
                        Map.of(
                                "phone",
                                phone,
                                "password",
                                password,
                                "displayName",
                                "新学生",
                                "challengeId",
                                challenge,
                                "code",
                                codes.get(challenge),
                                "termsVersion",
                                "2026-10-01"));
        var response =
                mvc.perform(
                                post("/api/v1/auth/student/register")
                                        .contentType("application/json")
                                        .content(registration))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.platform").value("student"))
                        .andReturn()
                        .getResponse();
        String access = json.readTree(response.getContentAsString()).get("accessToken").asText();
        mvc.perform(
                        post("/api/v1/auth/student/register")
                                .contentType("application/json")
                                .content(registration))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/auth/student/login")
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of("phone", phone, "password", password))))
                .andExpect(status().isOk());
        var resetResult =
                mvc.perform(
                                post("/api/v1/auth/challenges")
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "phone",
                                                                phone,
                                                                "purpose",
                                                                "RESET_PASSWORD",
                                                                "platform",
                                                                "student"))))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse();
        UUID reset =
                UUID.fromString(
                        json.readTree(resetResult.getContentAsString())
                                .get("challengeId")
                                .asText());
        mvc.perform(
                        post("/api/v1/auth/password-reset")
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "challengeId",
                                                        reset,
                                                        "code",
                                                        codes.get(reset),
                                                        "newPassword",
                                                        "new-password-0938!"))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/auth/student/login")
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of("phone", phone, "password", password))))
                .andExpect(status().isUnauthorized());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM user_account WHERE phone_digest=? AND"
                                + " platform='student'",
                        Integer.class,
                        digester.digest("phone", phone)));
    }

    @Test
    void verificationFailureCounterCommitsAndLimitsAttempts() throws Exception {
        var codes = new java.util.concurrent.ConcurrentHashMap<UUID, String>();
        org.mockito.Mockito.doAnswer(
                        call -> {
                            codes.put(call.getArgument(0), call.getArgument(2));
                            return null;
                        })
                .when(sms)
                .send(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        String phone = "13512345678";
        var response =
                mvc.perform(
                                post("/api/v1/auth/challenges")
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "phone",
                                                                phone,
                                                                "purpose",
                                                                "REGISTER"))))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse();
        UUID id =
                UUID.fromString(
                        json.readTree(response.getContentAsString()).get("challengeId").asText());
        String wrong = codes.get(id).equals("000000") ? "111111" : "000000";
        var input =
                new java.util.HashMap<String, Object>(
                        Map.of(
                                "phone",
                                phone,
                                "password",
                                "test-password-833",
                                "displayName",
                                "失败计数",
                                "challengeId",
                                id,
                                "code",
                                wrong,
                                "termsVersion",
                                "2026-10-01"));
        for (int i = 0; i < 5; i++)
            mvc.perform(
                            post("/api/v1/auth/student/register")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(input)))
                    .andExpect(status().isUnauthorized());
        assertEquals(
                5,
                db.queryForObject(
                        "SELECT attempts FROM verification_challenge WHERE id=?",
                        Integer.class,
                        id));
        input.put("code", codes.get(id));
        mvc.perform(
                        post("/api/v1/auth/student/register")
                                .contentType("application/json")
                                .content(json.writeValueAsString(input)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void smsFailureCannotBecomeUsableChallenge() throws Exception {
        org.mockito.Mockito.doThrow(
                        new cn.zhixiang.platform.shared.exception.BusinessException(
                                503, "PROVIDER_UNAVAILABLE", "短信未就绪"))
                .when(sms)
                .send(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        String phone = "13412345678";
        mvc.perform(
                        post("/api/v1/auth/challenges")
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of("phone", phone, "purpose", "REGISTER"))))
                .andExpect(status().isServiceUnavailable());
        assertEquals(
                "FAILED",
                db.queryForObject(
                        "SELECT state FROM verification_challenge WHERE phone_digest=?",
                        String.class,
                        digester.digest("phone", phone)));
    }

    @Test
    void administratorRequiresMfaAndRejectsStepReplay() throws Exception {
        UUID id = UUID.randomUUID(), ref = UUID.randomUUID();
        String phone = "13712345678", password = "admin-test-only-938!";
        String hash =
                org.springframework.security.crypto.factory.PasswordEncoderFactories
                        .createDelegatingPasswordEncoder()
                        .encode(password);
        db.update(
                "INSERT INTO"
                    + " user_account(id,platform,display_name,phone_digest,phone_masked,password_hash,status,mfa_secret_ref)"
                    + " VALUES(?,'admin',?,?,?,?,'ENABLED',?)",
                id,
                "测试管理员",
                digester.digest("phone", phone),
                "137****5678",
                hash,
                ref.toString());
        var dir = java.nio.file.Path.of("secrets/mfa");
        java.nio.file.Files.createDirectories(dir);
        var file = dir.resolve(ref.toString());
        // RFC标准测试种子只用于测试账户，结束后删除，不为真实用户配置固定种子。
        java.nio.file.Files.writeString(
                file,
                "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
                java.nio.file.StandardOpenOption.CREATE_NEW);
        java.nio.file.Files.setPosixFilePermissions(
                file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        try {
            mvc.perform(
                            post("/api/v1/auth/admin/login")
                                    .contentType("application/json")
                                    .content(
                                            json.writeValueAsString(
                                                    Map.of("phone", phone, "password", password))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
            String code =
                    cn.zhixiang.platform.identity.security.TotpVerifier.generate(
                            "12345678901234567890"
                                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                            java.time.Instant.now().getEpochSecond() / 30);
            String input =
                    json.writeValueAsString(
                            Map.of("phone", phone, "password", password, "mfaCode", code));
            mvc.perform(
                            post("/api/v1/auth/admin/login")
                                    .contentType("application/json")
                                    .content(input))
                    .andExpect(status().isOk());
            mvc.perform(
                            post("/api/v1/auth/admin/login")
                                    .contentType("application/json")
                                    .content(input))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("MFA_INVALID"));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void concurrentDuplicateWriteHasOneEffect() throws Exception {
        String key = UUID.randomUUID().toString();
        String body =
                json.writeValueAsString(
                        Map.of("name", "并发院校", "code", key, "region", "浙江", "status", "ENABLED"));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<String> call =
                    () -> {
                        start.await();
                        return mvc.perform(
                                        post("/api/v1/admin/schools")
                                                .header("Authorization", "Bearer " + adminToken)
                                                .header("Idempotency-Key", key)
                                                .contentType("application/json")
                                                .content(body))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();
                    };
            var one = pool.submit(call);
            var two = pool.submit(call);
            start.countDown();
            assertEquals(
                    one.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    two.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(
                1,
                db.queryForObject("SELECT count(*) FROM school WHERE code=?", Integer.class, key));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE actor_id=?",
                        Integer.class,
                        adminId));
    }

    @Test
    void realBrokerConfirmMarksOutboxSent() throws Exception {
        String name = "zx.test." + UUID.randomUUID();
        var admin =
                new org.springframework.amqp.rabbit.core.RabbitAdmin(rabbit.getConnectionFactory());
        var exchange = new org.springframework.amqp.core.DirectExchange(name, false, true);
        var queue = new org.springframework.amqp.core.Queue(name, false, false, false);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(
                org.springframework.amqp.core.BindingBuilder.bind(queue).to(exchange).with("test"));
        UUID event = UUID.randomUUID();
        try {
            db.update(
                    "INSERT INTO outbox_event(id,aggregate_id,event_type,payload)"
                            + " VALUES(?,?,?,'{}')",
                    event,
                    UUID.randomUUID(),
                    "test.probe");
            new cn.zhixiang.platform.operations.worker.OutboxPublisher(
                            outbox, rabbit, json, name, "test")
                    .publish();
            assertEquals(
                    "SENT",
                    db.queryForObject(
                            "SELECT status FROM outbox_event WHERE id=?", String.class, event));
            boolean found = false;
            org.springframework.amqp.core.Message message;
            while ((message = rabbit.receive(name, 500)) != null)
                if (event.toString().equals(message.getMessageProperties().getMessageId()))
                    found = true;
            assertTrue(found, "真实队列必须收到目标事件");
        } finally {
            admin.deleteQueue(name);
            admin.deleteExchange(name);
        }
    }

    @Test
    void loginRefreshReplayAndCsrf() throws Exception {
        UUID student = UUID.randomUUID();
        String phone = "13912345678", password = "test-only-password-928!";
        String hash =
                org.springframework.security.crypto.factory.PasswordEncoderFactories
                        .createDelegatingPasswordEncoder()
                        .encode(password);
        db.update(
                "INSERT INTO"
                    + " user_account(id,platform,display_name,phone_digest,phone_masked,password_hash,status)"
                    + " VALUES(?,'student',?,?,?,?,'ENABLED')",
                student,
                "测试学生",
                digester.digest("phone", phone),
                "139****5678",
                hash);
        String input = json.writeValueAsString(Map.of("phone", phone, "password", password));
        var login =
                mvc.perform(
                                post("/api/v1/auth/student/login")
                                        .contentType("application/json")
                                        .content(input))
                        .andExpect(status().isOk())
                        .andExpect(cookie().httpOnly("zx_student_refresh", true))
                        .andExpect(cookie().secure("zx_student_refresh", true))
                        .andExpect(jsonPath("$.user.platform").value("student"))
                        .andReturn()
                        .getResponse();
        var data = json.readTree(login.getContentAsString());
        String access = data.get("accessToken").asText(), csrf = data.get("csrfToken").asText();
        var refresh = login.getCookie("zx_student_refresh");
        var csrfCookie = login.getCookie("zx_student_csrf");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/schools").header("Authorization", "Bearer " + access))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/auth/student/refresh")
                                .cookie(refresh, csrfCookie)
                                .header("X-CSRF-Token", csrf)
                                .header("Origin", "https://evil.invalid"))
                .andExpect(status().isForbidden());
        var rotated =
                mvc.perform(
                                post("/api/v1/auth/student/refresh")
                                        .cookie(refresh, csrfCookie)
                                        .header("X-CSRF-Token", csrf)
                                        .header("Origin", "https://student.invalid"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        String newAccess = json.readTree(rotated.getContentAsString()).get("accessToken").asText();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/auth/student/refresh")
                                .cookie(refresh, csrfCookie)
                                .header("X-CSRF-Token", csrf)
                                .header("Origin", "https://student.invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REPLAY"));
        // 撤销必须在抛业务异常前提交，验证新令牌也失效。
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authorizationAndImmediateRevocation() throws Exception {
        mvc.perform(get("/api/v1/admin/schools")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/schools").header("Authorization", "Bearer " + partnerToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/schools").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        db.update("UPDATE user_account SET auth_version=auth_version+1 WHERE id=?", adminId);
        mvc.perform(get("/api/v1/admin/schools").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void crudIdempotencyVersionAndReferenceProtection() throws Exception {
        String key = UUID.randomUUID().toString();
        String body =
                "{\"name\":\"测试学院\",\"code\":\""
                        + UUID.randomUUID()
                        + "\",\"region\":\"浙江\",\"status\":\"ENABLED\"}";
        var first =
                mvc.perform(
                                post("/api/v1/admin/schools")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", key)
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andExpect(header().string("ETag", "\"1\""))
                        .andReturn();
        String saved = first.getResponse().getContentAsString();
        String id = json.readTree(saved).get("id").asText();
        mvc.perform(
                        post("/api/v1/admin/schools")
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", key)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(content().json(saved));
        mvc.perform(
                        post("/api/v1/admin/schools")
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", key)
                                .contentType("application/json")
                                .content(body.replace("浙江", "江苏")))
                .andExpect(status().isConflict());
        mvc.perform(
                        put("/api/v1/admin/schools/" + id)
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .header("If-Match", "\"2\"")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(
                        get("/api/v1/admin/schools")
                                .header("Authorization", "Bearer " + adminToken)
                                .param("q", "测试学院"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id));
        String major =
                "{\"name\":\"计算机\",\"code\":\"CS\",\"schoolId\":\""
                        + id
                        + "\",\"level\":\"BACHELOR\",\"status\":\"ENABLED\"}";
        mvc.perform(
                        post("/api/v1/admin/majors")
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(major))
                .andExpect(status().isCreated());
        mvc.perform(
                        delete("/api/v1/admin/schools/" + id)
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .header("If-Match", "\"1\""))
                .andExpect(status().isConflict());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE actor_id=?",
                        Integer.class,
                        adminId));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM outbox_event WHERE aggregate_id IN (SELECT"
                                + " resource_id FROM audit_event WHERE actor_id=?)",
                        Integer.class,
                        adminId));
    }

    @Test
    void roleScopeBuiltInAndReferenceProtection() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        try {
            String code = "TEST_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
            String body =
                    json.writeValueAsString(
                            Map.of(
                                    "name",
                                    "资料维护员",
                                    "code",
                                    code,
                                    "platform",
                                    "admin",
                                    "dataScope",
                                    "ALL",
                                    "permissionCodes",
                                    List.of("schools:read"),
                                    "status",
                                    "ENABLED"));
            var created =
                    mvc.perform(
                                    post("/api/v1/admin/roles")
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(body))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse();
            UUID role =
                    UUID.fromString(json.readTree(created.getContentAsString()).get("id").asText());
            mvc.perform(
                            post("/api/v1/admin/roles")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(body.replace("ALL", "SELF")))
                    .andExpect(status().isUnprocessableEntity());
            mvc.perform(
                            delete("/api/v1/admin/roles/" + superRole)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\""))
                    .andExpect(status().isForbidden());
            UUID target = UUID.randomUUID();
            String token = UUID.randomUUID().toString();
            seed(target, "admin", token);
            db.update("INSERT INTO user_role VALUES(?,?)", target, role);
            mvc.perform(
                            delete("/api/v1/admin/roles/" + role)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\""))
                    .andExpect(status().isConflict());
            mvc.perform(get("/api/v1/admin/schools").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            mvc.perform(
                            post("/api/v1/admin/roles/" + role + "/status")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content("{\"status\":\"DISABLED\"}"))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/admin/schools").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        } finally {
            db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", adminId, superRole);
        }
    }

    @Test
    void adminInvitationMfaActivationAndLastAdminProtection() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        db.update("UPDATE user_account SET activated_at=now() WHERE id=?", adminId);
        UUID createdUser = null;
        try {
            mvc.perform(
                            post("/api/v1/admin/users/" + adminId + "/status")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content("{\"status\":\"DISABLED\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LAST_ADMIN_REQUIRED"));
            String phone = "13512345678";
            var created =
                    mvc.perform(
                                    post("/api/v1/admin/users")
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(
                                                    json.writeValueAsString(
                                                            Map.of(
                                                                    "phone",
                                                                    phone,
                                                                    "displayName",
                                                                    "新管理员",
                                                                    "platform",
                                                                    "admin",
                                                                    "roleIds",
                                                                    List.of(superRole)))))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse();
            createdUser =
                    UUID.fromString(json.readTree(created.getContentAsString()).get("id").asText());
            mvc.perform(
                            post("/api/v1/admin/users/" + createdUser + "/status")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content("{\"status\":\"ENABLED\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ACTIVATION_REQUIRED"));
            String path = "/api/v1/admin/users/" + createdUser + "/invitations",
                    key = UUID.randomUUID().toString();
            var invited =
                    mvc.perform(
                                    post(path)
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", key)
                                            .contentType("application/json")
                                            .content("{\"expiresInHours\":1}"))
                            .andExpect(status().isCreated())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andReturn()
                            .getResponse();
            String link = json.readTree(invited.getContentAsString()).get("activationUrl").asText(),
                    token = link.substring(link.indexOf("#token=") + 7);
            mvc.perform(
                            post(path)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", key)
                                    .contentType("application/json")
                                    .content("{\"expiresInHours\":1}"))
                    .andExpect(status().isCreated())
                    .andExpect(content().json(invited.getContentAsString()));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM idempotency_record WHERE response_body LIKE ?",
                            Integer.class,
                            "%" + token + "%"));
            var enrolled =
                    mvc.perform(
                                    post("/api/v1/auth/admin/mfa-enrollments")
                                            .contentType("application/json")
                                            .content(
                                                    json.writeValueAsString(
                                                            Map.of("invitationToken", token))))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse();
            var enrollment = json.readTree(enrolled.getContentAsString());
            String uri = enrollment.get("otpAuthUri").asText(),
                    secret = uri.split("secret=")[1].split("&")[0];
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM secret_material WHERE ciphertext LIKE ?",
                            Integer.class,
                            "%" + secret + "%"));
            var out = new java.io.ByteArrayOutputStream();
            int buffer = 0, bits = 0;
            for (char c : secret.toCharArray()) {
                buffer = (buffer << 5) | "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
                bits += 5;
                if (bits >= 8) {
                    bits -= 8;
                    out.write((buffer >> bits) & 255);
                }
            }
            String otp =
                    cn.zhixiang.platform.identity.security.TotpVerifier.generate(
                            out.toByteArray(), java.time.Instant.now().getEpochSecond() / 30);
            String activation =
                    json.writeValueAsString(
                            Map.of(
                                    "invitationToken",
                                    token,
                                    "password",
                                    "admin-test-password-2026",
                                    "mfaEnrollmentId",
                                    enrollment.get("enrollmentId").asText(),
                                    "mfaCode",
                                    otp));
            var result =
                    mvc.perform(
                                    post("/api/v1/auth/admin/activate")
                                            .contentType("application/json")
                                            .content(activation))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.platform").value("admin"))
                            .andReturn()
                            .getResponse();
            String access = json.readTree(result.getContentAsString()).get("accessToken").asText();
            mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + access))
                    .andExpect(status().isOk());
            mvc.perform(
                            post("/api/v1/auth/admin/activate")
                                    .contentType("application/json")
                                    .content(activation))
                    .andExpect(status().isUnauthorized());
            mvc.perform(
                            post("/api/v1/auth/admin/login")
                                    .contentType("application/json")
                                    .content(
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "phone",
                                                            phone,
                                                            "password",
                                                            "admin-test-password-2026",
                                                            "mfaCode",
                                                            otp))))
                    .andExpect(status().isUnauthorized());
        } finally {
            db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", adminId, superRole);
            if (createdUser != null)
                db.update(
                        "DELETE FROM user_role WHERE user_id=? AND role_id=?",
                        createdUser,
                        superRole);
        }
    }

    @Test
    void partnerInvitationRequiresPhoneProofAndRejectsReplacedToken() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        var codes = new java.util.concurrent.ConcurrentHashMap<UUID, String>();
        org.mockito.Mockito.doAnswer(
                        call -> {
                            codes.put(call.getArgument(0), call.getArgument(2));
                            return null;
                        })
                .when(sms)
                .send(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        try {
            String phone = "13412345678";
            var invited =
                    mvc.perform(
                                    post("/api/v1/admin/partner-invitations")
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(
                                                    json.writeValueAsString(
                                                            Map.of(
                                                                    "phone",
                                                                    phone,
                                                                    "displayName",
                                                                    "受邀中介",
                                                                    "expiresInHours",
                                                                    1))))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse();
            String link = json.readTree(invited.getContentAsString()).get("activationUrl").asText(),
                    old = link.split("#token=")[1];
            UUID user =
                    db.queryForObject(
                            "SELECT id FROM user_account WHERE platform='partner' AND"
                                    + " phone_digest=?",
                            UUID.class,
                            digester.digest("phone", phone));
            var renewed =
                    mvc.perform(
                                    post("/api/v1/admin/users/" + user + "/invitations")
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content("{\"expiresInHours\":1}"))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse();
            String token =
                    json.readTree(renewed.getContentAsString())
                            .get("activationUrl")
                            .asText()
                            .split("#token=")[1];
            var sent =
                    mvc.perform(
                                    post("/api/v1/auth/challenges")
                                            .contentType("application/json")
                                            .content(
                                                    json.writeValueAsString(
                                                            Map.of(
                                                                    "phone",
                                                                    phone,
                                                                    "purpose",
                                                                    "ACTIVATE",
                                                                    "platform",
                                                                    "partner"))))
                            .andExpect(status().isAccepted())
                            .andReturn()
                            .getResponse();
            UUID challenge =
                    UUID.fromString(
                            json.readTree(sent.getContentAsString()).get("challengeId").asText());
            Map<String, Object> input =
                    new HashMap<>(
                            Map.of(
                                    "phone",
                                    phone,
                                    "password",
                                    "partner-password-2026",
                                    "displayName",
                                    "中介",
                                    "termsVersion",
                                    "2026-10-01",
                                    "invitationToken",
                                    old,
                                    "challengeId",
                                    challenge,
                                    "code",
                                    codes.get(challenge)));
            mvc.perform(
                            post("/api/v1/auth/partner/activate")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(input)))
                    .andExpect(status().isUnauthorized());
            input.put("invitationToken", token);
            input.put("code", codes.get(challenge).equals("000000") ? "000001" : "000000");
            mvc.perform(
                            post("/api/v1/auth/partner/activate")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(input)))
                    .andExpect(status().isUnauthorized());
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT attempts FROM verification_challenge WHERE id=?",
                            Integer.class,
                            challenge));
            input.put("code", codes.get(challenge));
            var activated =
                    mvc.perform(
                                    post("/api/v1/auth/partner/activate")
                                            .contentType("application/json")
                                            .content(json.writeValueAsString(input)))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.platform").value("partner"))
                            .andReturn()
                            .getResponse();
            String access =
                    json.readTree(activated.getContentAsString()).get("accessToken").asText();
            mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + access))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get("/api/v1/student/conversations")
                                    .header("Authorization", "Bearer " + access))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            post("/api/v1/auth/partner/activate")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(input)))
                    .andExpect(status().isUnauthorized());
        } finally {
            db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", adminId, superRole);
        }
    }

    @Test
    void delegatedRoleWriterCannotEscalateOrEditHigherRole() throws Exception {
        UUID ownRole =
                db.queryForObject(
                        "SELECT role_id FROM user_role WHERE user_id=?", UUID.class, adminId);
        db.update("INSERT INTO role_permission VALUES(?,'roles:write')", ownRole);
        String body =
                json.writeValueAsString(
                        Map.of(
                                "name",
                                "越权角色",
                                "code",
                                "ESC_"
                                        + UUID.randomUUID()
                                                .toString()
                                                .replace("-", "")
                                                .toUpperCase(),
                                "platform",
                                "admin",
                                "dataScope",
                                "ALL",
                                "permissionCodes",
                                List.of("post_admin_users"),
                                "status",
                                "ENABLED"));
        mvc.perform(
                        post("/api/v1/admin/roles")
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden());
        UUID higher = UUID.randomUUID();
        db.update(
                "INSERT INTO role(id,code,name,platform,data_scope,status)"
                        + " VALUES(?,?,?,'admin','ALL','ENABLED')",
                higher,
                higher.toString(),
                "高权限");
        db.update("INSERT INTO role_permission VALUES(?,'post_admin_users')", higher);
        String lower = body.replace("post_admin_users", "schools:read");
        mvc.perform(
                        put("/api/v1/admin/roles/" + higher)
                                .header("Authorization", "Bearer " + adminToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .header("If-Match", "\"1\"")
                                .contentType("application/json")
                                .content(lower))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportImplementedRoutesForContractReview() throws Exception {
        var rows = new java.util.ArrayList<Map<String, String>>();
        handlers.getHandlerMethods()
                .forEach(
                        (mapping, method) -> {
                            var permission =
                                    method.getMethodAnnotation(
                                            org.springframework.security.access.prepost.PreAuthorize
                                                    .class);
                            for (String path : mapping.getPatternValues())
                                if (path.startsWith("/api/v1/"))
                                    for (var verb : mapping.getMethodsCondition().getMethods()) {
                                        List<String> paths =
                                                path.contains("{platform:")
                                                        ? List.of(
                                                                path.replaceAll(
                                                                        "\\{platform:[^}]+}",
                                                                        "student"),
                                                                path.replaceAll(
                                                                        "\\{platform:[^}]+}",
                                                                        "partner"),
                                                                path.replaceAll(
                                                                        "\\{platform:[^}]+}",
                                                                        "admin"))
                                                        : List.of(path);
                                        for (String concrete : paths)
                                            rows.add(
                                                    Map.of(
                                                            "method",
                                                            verb.name(),
                                                            "path",
                                                            concrete,
                                                            "permission",
                                                            permission == null
                                                                    ? ""
                                                                    : permission.value()));
                                    }
                        });
        rows.sort(java.util.Comparator.comparing(r -> r.get("path") + r.get("method")));
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target/implemented-routes.json"),
                json.writeValueAsString(rows));
        assertTrue(rows.size() > 30);
    }

    @Test
    void membershipSnapshotsVersioningAndRevocation() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        try {
            UUID student = UUID.randomUUID();
            String token = UUID.randomUUID().toString();
            seed(student, "student", token);
            db.update(
                    "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')",
                    student);
            String body =
                    json.writeValueAsString(
                            Map.of(
                                    "name",
                                    "标准答疑会员",
                                    "code",
                                    UUID.randomUUID().toString(),
                                    "durationDays",
                                    30,
                                    "monthlyTokenQuota",
                                    100000,
                                    "monthlyAmountLimit",
                                    "20.123456",
                                    "status",
                                    "ENABLED"));
            var plan =
                    mvc.perform(
                                    post("/api/v1/admin/membership-plans")
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(body))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.monthlyAmountLimit").value("20.123456"))
                            .andReturn()
                            .getResponse();
            UUID planId =
                    UUID.fromString(json.readTree(plan.getContentAsString()).get("id").asText());
            String grant =
                    json.writeValueAsString(
                            Map.of(
                                    "planId",
                                    planId,
                                    "startsAt",
                                    java.time.OffsetDateTime.now().minusMinutes(1).toString(),
                                    "reason",
                                    "测试发放"));
            String path = "/api/v1/admin/users/" + student + "/memberships",
                    key = UUID.randomUUID().toString();
            var member =
                    mvc.perform(
                                    post(path)
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", key)
                                            .contentType("application/json")
                                            .content(grant))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.state").value("ACTIVE"))
                            .andReturn()
                            .getResponse();
            UUID memberId =
                    UUID.fromString(json.readTree(member.getContentAsString()).get("id").asText());
            UUID originalRevision =
                    db.queryForObject(
                            "SELECT plan_version_id FROM membership WHERE id=?",
                            UUID.class,
                            memberId);
            mvc.perform(
                            post(path)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", key)
                                    .contentType("application/json")
                                    .content(grant))
                    .andExpect(status().isCreated())
                    .andExpect(content().json(member.getContentAsString()));
            mvc.perform(
                            get("/api/v1/student/membership")
                                    .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(memberId.toString()));
            mvc.perform(
                            put("/api/v1/admin/membership-plans/" + planId)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("If-Match", "\"1\"")
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(body.replace("100000", "200000")))
                    .andExpect(status().isUnprocessableEntity());
            String revision =
                    json.writeValueAsString(
                            Map.of(
                                    "durationDays",
                                    60,
                                    "monthlyTokenQuota",
                                    200000,
                                    "monthlyAmountLimit",
                                    "30.5",
                                    "effectiveFrom",
                                    java.time.OffsetDateTime.now().plusDays(1).toString()));
            mvc.perform(
                            post("/api/v1/admin/membership-plans/" + planId + "/versions")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("If-Match", "\"1\"")
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(revision))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.version").value(2));
            assertEquals(
                    originalRevision,
                    db.queryForObject(
                            "SELECT plan_version_id FROM membership WHERE id=?",
                            UUID.class,
                            memberId));
            mvc.perform(
                            get("/api/v1/admin/membership-plans/" + planId)
                                    .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.monthlyTokenQuota").value(100000))
                    .andExpect(header().string("ETag", "\"2\""));
            mvc.perform(
                            get("/api/v1/admin/membership-plans/" + planId + "/versions")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("size", "1")
                                    .param("sort", "createdAt:asc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.items[0].version").value(1));
            mvc.perform(
                            delete("/api/v1/admin/membership-plans/" + planId)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("If-Match", "\"2\"")
                                    .header("Idempotency-Key", UUID.randomUUID()))
                    .andExpect(status().isConflict());
            mvc.perform(
                            post("/api/v1/admin/memberships/" + memberId + "/revoke")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("If-Match", "\"1\"")
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content("{\"reason\":\"撤销测试\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("REVOKED"));
            mvc.perform(
                            get("/api/v1/student/membership")
                                    .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            get(path)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("status", "REVOKED")
                                    .param("q", "标准答疑"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
            assertEquals(
                    "撤销测试",
                    db.queryForObject(
                            "SELECT detail->>'reason' FROM audit_event WHERE resource_id=? AND"
                                    + " action='REVOKED'",
                            String.class,
                            memberId));
        } finally {
            db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", adminId, superRole);
        }
    }

    @Test
    void concurrentMembershipGrantsNeverOverlapAndScheduledIsNotActive() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        try {
            UUID student = UUID.randomUUID();
            String token = UUID.randomUUID().toString();
            seed(student, "student", token);
            db.update(
                    "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')",
                    student);
            String body =
                    json.writeValueAsString(
                            Map.of(
                                    "name",
                                    "未来会员",
                                    "code",
                                    UUID.randomUUID().toString(),
                                    "durationDays",
                                    10,
                                    "monthlyTokenQuota",
                                    1000,
                                    "monthlyAmountLimit",
                                    "1",
                                    "status",
                                    "ENABLED"));
            var response =
                    mvc.perform(
                                    post("/api/v1/admin/membership-plans")
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(body))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse();
            UUID plan =
                    UUID.fromString(
                            json.readTree(response.getContentAsString()).get("id").asText());
            String grant =
                    json.writeValueAsString(
                            Map.of(
                                    "planId",
                                    plan,
                                    "startsAt",
                                    java.time.OffsetDateTime.now().plusDays(1).toString(),
                                    "reason",
                                    "并发测试"));
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                java.util.concurrent.Callable<Integer> call =
                        () -> {
                            start.await();
                            return mvc.perform(
                                            post("/api/v1/admin/users/" + student + "/memberships")
                                                    .header("Authorization", "Bearer " + adminToken)
                                                    .header("Idempotency-Key", UUID.randomUUID())
                                                    .contentType("application/json")
                                                    .content(grant))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus();
                        };
                var a = pool.submit(call);
                var b = pool.submit(call);
                start.countDown();
                assertEquals(
                        Set.of(201, 409),
                        Set.of(
                                a.get(10, java.util.concurrent.TimeUnit.SECONDS),
                                b.get(10, java.util.concurrent.TimeUnit.SECONDS)));
            }
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM membership WHERE user_id=?",
                            Integer.class,
                            student));
            mvc.perform(
                            get("/api/v1/admin/users/" + student + "/memberships")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("status", "SCHEDULED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
            mvc.perform(
                            get("/api/v1/student/membership")
                                    .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            get("/api/v1/admin/membership-plans")
                                    .header("Authorization", "Bearer " + partnerToken))
                    .andExpect(status().isForbidden());
        } finally {
            db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", adminId, superRole);
        }
    }

    @Test
    void menuTreeScopePermissionFilteringAndCycleProtection() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        try {
            UUID student = UUID.randomUUID();
            String token = UUID.randomUUID().toString();
            seed(student, "student", token);
            UUID role = UUID.randomUUID();
            db.update(
                    "INSERT INTO role(id,code,name,platform,data_scope,status)"
                            + " VALUES(?,?,?,'student','SELF','ENABLED')",
                    role,
                    role.toString(),
                    "菜单受限角色");
            db.update("INSERT INTO user_role VALUES(?,?)", student, role);
            for (String permission :
                    List.of("get_student_dictionaries_menus", "get_student_membership"))
                db.update("INSERT INTO role_permission VALUES(?,?)", role, permission);
            String unique = UUID.randomUUID().toString();
            Map<String, Object> root = new HashMap<>();
            root.put("name", "会员入口");
            root.put("code", unique);
            root.put("platform", "student");
            root.put("parentId", null);
            root.put("path", "/membership-" + unique);
            root.put("permissionCode", "get_student_membership");
            root.put("sortOrder", 0);
            root.put("status", "ENABLED");
            var created =
                    mvc.perform(
                                    post("/api/v1/admin/menus")
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(json.writeValueAsString(root)))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse();
            String parent = json.readTree(created.getContentAsString()).get("id").asText();
            Map<String, Object> child = new HashMap<>(root);
            child.put("name", "需另一权限");
            child.put("code", unique + "child");
            child.put("path", "/child-" + unique);
            child.put("parentId", parent);
            child.put("permissionCode", "get_student_dictionaries_menus");
            var childResponse =
                    mvc.perform(
                                    post("/api/v1/admin/menus")
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(json.writeValueAsString(child)))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse();
            String childId = json.readTree(childResponse.getContentAsString()).get("id").asText();
            mvc.perform(
                            get("/api/v1/student/dictionaries/menus")
                                    .header("Authorization", "Bearer " + token)
                                    .param("q", unique))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2));
            root.put("parentId", childId);
            mvc.perform(
                            put("/api/v1/admin/menus/" + parent)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("If-Match", "\"1\"")
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(root)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("MENU_CYCLE"));
            mvc.perform(
                            post("/api/v1/admin/menus/" + parent + "/status")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("If-Match", "\"1\"")
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content("{\"status\":\"DISABLED\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(
                            delete("/api/v1/admin/menus/" + parent)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("If-Match", "\"1\"")
                                    .header("Idempotency-Key", UUID.randomUUID()))
                    .andExpect(status().isConflict());
            // 删除父项权限后，拥有子项权限也不能绕过整条祖先链。
            db.update(
                    "DELETE FROM role_permission WHERE role_id=? AND"
                            + " permission_code='get_student_membership'",
                    role);
            mvc.perform(
                            get("/api/v1/student/dictionaries/menus")
                                    .header("Authorization", "Bearer " + token)
                                    .param("q", unique))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            child.put("permissionCode", "menus:write");
            mvc.perform(
                            post("/api/v1/admin/menus")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(child)))
                    .andExpect(status().isUnprocessableEntity());
            child.put("path", "//evil.invalid");
            mvc.perform(
                            post("/api/v1/admin/menus")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(child)))
                    .andExpect(status().isBadRequest());
        } finally {
            db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", adminId, superRole);
        }
    }

    @Test
    void taxonomyCrudLiteralSearchAndTreeInvariants() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        try {
            String code = "tag_%_" + UUID.randomUUID();
            String input =
                    json.writeValueAsString(
                            Map.of("name", "精确标签", "code", code, "status", "ENABLED"));
            var tag =
                    mvc.perform(
                                    post("/api/v1/admin/tags")
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(input))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse();
            String id = json.readTree(tag.getContentAsString()).get("id").asText();
            mvc.perform(
                            get("/api/v1/admin/tags")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("q", code))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
            mvc.perform(
                            post("/api/v1/admin/tags")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(input))
                    .andExpect(status().isConflict());
            mvc.perform(
                            put("/api/v1/admin/tags/" + id)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("If-Match", "\"1\"")
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(input.replace("精确标签", "更新标签")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", "\"2\""));
            mvc.perform(
                            post("/api/v1/admin/tags/" + id + "/status")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("If-Match", "\"2\"")
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content("{\"status\":\"DISABLED\"}"))
                    .andExpect(status().isOk());
            UUID student = UUID.randomUUID();
            String token = UUID.randomUUID().toString();
            seed(student, "student", token);
            db.update(
                    "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')",
                    student);
            mvc.perform(
                            get("/api/v1/student/dictionaries/tags")
                                    .header("Authorization", "Bearer " + token)
                                    .param("q", code))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            mvc.perform(
                            delete("/api/v1/admin/tags/" + id)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("If-Match", "\"3\"")
                                    .header("Idempotency-Key", UUID.randomUUID()))
                    .andExpect(status().isNoContent());
            Map<String, Object> root = new HashMap<>();
            root.put("name", "知识根分类");
            root.put("code", UUID.randomUUID().toString());
            root.put("parentId", null);
            root.put("sortOrder", 1);
            root.put("status", "ENABLED");
            var category =
                    mvc.perform(
                                    post("/api/v1/admin/categories")
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(json.writeValueAsString(root)))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse();
            String parent = json.readTree(category.getContentAsString()).get("id").asText();
            Map<String, Object> child = new HashMap<>(root);
            child.put("parentId", parent);
            child.put("code", UUID.randomUUID().toString());
            var createdChild =
                    mvc.perform(
                                    post("/api/v1/admin/categories")
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(json.writeValueAsString(child)))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse();
            String childId = json.readTree(createdChild.getContentAsString()).get("id").asText();
            root.put("parentId", childId);
            mvc.perform(
                            put("/api/v1/admin/categories/" + parent)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("If-Match", "\"1\"")
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(root)))
                    .andExpect(status().isUnprocessableEntity());
            mvc.perform(
                            delete("/api/v1/admin/categories/" + parent)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("If-Match", "\"1\"")
                                    .header("Idempotency-Key", UUID.randomUUID()))
                    .andExpect(status().isConflict());
            mvc.perform(
                            post("/api/v1/admin/categories/" + parent + "/status")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("If-Match", "\"1\"")
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content("{\"status\":\"DISABLED\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(
                            get("/api/v1/student/dictionaries/categories")
                                    .header("Authorization", "Bearer " + token)
                                    .param("status", "DISABLED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
        } finally {
            db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", adminId, superRole);
        }
    }

    @Test
    void modelConfigurationValidationAndCas() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        try {
            Map<String, Object> body =
                    new HashMap<>(
                            Map.of(
                                    "name",
                                    "测试模型",
                                    "code",
                                    UUID.randomUUID().toString(),
                                    "provider",
                                    "OPENAI_COMPATIBLE",
                                    "endpoint",
                                    "https://example.invalid/v1",
                                    "contextLimit",
                                    8192,
                                    "maxOutputTokens",
                                    1024,
                                    "status",
                                    "ENABLED"));
            String path = "/api/v1/admin/models", key = UUID.randomUUID().toString();
            var response =
                    mvc.perform(
                                    post(path)
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", key)
                                            .contentType("application/json")
                                            .content(json.writeValueAsString(body)))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse();
            String id = json.readTree(response.getContentAsString()).get("id").asText();
            mvc.perform(
                            post(path)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", key)
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andExpect(content().json(response.getContentAsString()));
            body.put("maxOutputTokens", 8193);
            mvc.perform(
                            put(path + "/" + id)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isUnprocessableEntity());
            body.put("maxOutputTokens", 2048);
            body.put("endpoint", "https://secret@example.invalid/v1");
            mvc.perform(
                            put(path + "/" + id)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isUnprocessableEntity());
            body.put("endpoint", "https://example.invalid/v1");
            mvc.perform(
                            put(path + "/" + id)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(2));
            mvc.perform(
                            delete(path + "/" + id)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\""))
                    .andExpect(status().isPreconditionFailed());
            mvc.perform(get(path).header("Authorization", "Bearer " + partnerToken))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get(path)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("q", body.get("code").toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
            mvc.perform(
                            delete(path + "/" + id)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"2\""))
                    .andExpect(status().isNoContent());
        } finally {
            db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", adminId, superRole);
        }
    }

    @Test
    void modelPriceHistoryAndSecretRotation() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        try {
            String models = "/api/v1/admin/models",
                    prices = "/api/v1/admin/model-prices",
                    keys = "/api/v1/admin/model-keys";
            Map<String, Object> model =
                    new HashMap<>(
                            Map.of(
                                    "name",
                                    "带价格的模型",
                                    "code",
                                    UUID.randomUUID().toString(),
                                    "provider",
                                    "OPENAI_COMPATIBLE",
                                    "endpoint",
                                    "https://example.invalid/v1",
                                    "contextLimit",
                                    8192,
                                    "maxOutputTokens",
                                    1024,
                                    "status",
                                    "ENABLED"));
            String mid =
                    json.readTree(
                                    mvc.perform(
                                                    post(models)
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + adminToken)
                                                            .header(
                                                                    "Idempotency-Key",
                                                                    UUID.randomUUID())
                                                            .contentType("application/json")
                                                            .content(
                                                                    json.writeValueAsString(model)))
                                            .andExpect(status().isCreated())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                            .get("id")
                            .asText();
            var price =
                    new HashMap<String, Object>(
                            Map.of(
                                    "modelId",
                                    mid,
                                    "currency",
                                    "CNY",
                                    "inputPerMillion",
                                    "1.123456",
                                    "outputPerMillion",
                                    "2.500000",
                                    "cachedInputPerMillion",
                                    "0.100000",
                                    "effectiveFrom",
                                    java.time.OffsetDateTime.now().plusHours(1).toString(),
                                    "status",
                                    "ENABLED"));
            var response =
                    mvc.perform(
                                    post(prices)
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(json.writeValueAsString(price)))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.inputPerMillion").value("1.123456"))
                            .andReturn()
                            .getResponse();
            String pid = json.readTree(response.getContentAsString()).get("id").asText();
            mvc.perform(
                            get(prices)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("q", model.get("code").toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
            model.put("endpoint", "https://another.invalid/v1");
            mvc.perform(
                            put(models + "/" + mid)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(model)))
                    .andExpect(status().isConflict());
            mvc.perform(
                            delete(models + "/" + mid)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\""))
                    .andExpect(status().isConflict());
            // 仅隔离 schema 推进生效时间，避免以睡眠测试时间敏感规则。
            db.update(
                    "UPDATE model_price SET effective_from=now()-interval '1 minute' WHERE id=?",
                    UUID.fromString(pid));
            mvc.perform(
                            put(prices + "/" + pid)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(price)))
                    .andExpect(status().isConflict());
            mvc.perform(
                            delete(prices + "/" + pid)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\""))
                    .andExpect(status().isConflict());
            String secret = "isolated-test-secret-" + UUID.randomUUID();
            String keyBody =
                    json.writeValueAsString(
                            Map.of("name", "测试凭证", "modelId", mid, "secretValue", secret));
            var kr =
                    mvc.perform(
                                    post(keys)
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(keyBody))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.status").value("DISABLED"))
                            .andExpect(jsonPath("$.secretValue").doesNotExist())
                            .andReturn()
                            .getResponse();
            String kid = json.readTree(kr.getContentAsString()).get("id").asText();
            assertFalse(kr.getContentAsString().contains(secret));
            UUID oldRef =
                    db.queryForObject(
                            "SELECT secret_ref FROM model_key WHERE id=?",
                            UUID.class,
                            UUID.fromString(kid));
            String ciphertext =
                    db.queryForObject(
                            "SELECT ciphertext FROM secret_material WHERE id=?",
                            String.class,
                            oldRef);
            assertFalse(ciphertext.contains(secret));
            String rotated = "isolated-new-secret-" + UUID.randomUUID(),
                    idem = UUID.randomUUID().toString();
            String rotation =
                    json.writeValueAsString(Map.of("secretValue", rotated, "reason", "例行轮换"));
            for (int n = 0; n < 2; n++)
                mvc.perform(
                                post(keys + "/" + kid + "/rotate")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", idem)
                                        .header("If-Match", "\"1\"")
                                        .contentType("application/json")
                                        .content(rotation))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.version").value(2));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM secret_material WHERE id=?",
                            Integer.class,
                            oldRef));
            mvc.perform(
                            get(keys)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("q", "测试凭证"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].secretRef").doesNotExist());
            mvc.perform(
                            delete(keys + "/" + kid)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"2\""))
                    .andExpect(status().isNoContent());
        } finally {
            db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", adminId, superRole);
        }
    }

    @Test
    void storageAndDomainConfigurationBoundaries() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        try {
            String path = "/api/v1/admin/storages";
            var body =
                    new HashMap<String, Object>(
                            Map.of(
                                    "name",
                                    "本地私有存储",
                                    "provider",
                                    "S3_COMPATIBLE",
                                    "bucket",
                                    "test-" + UUID.randomUUID(),
                                    "region",
                                    "us-east-1",
                                    "endpoint",
                                    "http://127.0.0.1:19000",
                                    "credentialRef",
                                    "local-minio",
                                    "access",
                                    "PRIVATE",
                                    "status",
                                    "ENABLED"));
            var response =
                    mvc.perform(
                                    post(path)
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(json.writeValueAsString(body)))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse();
            String id = json.readTree(response.getContentAsString()).get("id").asText();
            body.put("endpoint", "http://external.invalid");
            mvc.perform(
                            put(path + "/" + id)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isUnprocessableEntity());
            body.put("endpoint", "https://storage.invalid");
            mvc.perform(
                            put(path + "/" + id)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isConflict());
            mvc.perform(
                            post(path + "/" + id + "/status")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content("{\"status\":\"DISABLED\"}"))
                    .andExpect(status().isOk());
            mvc.perform(
                            delete(path + "/" + id)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"2\""))
                    .andExpect(status().isNoContent());
            String domains = "/api/v1/admin/domains",
                    host = "ZX-" + UUID.randomUUID() + ".EXAMPLE.COM";
            var domain =
                    new HashMap<String, Object>(
                            Map.of(
                                    "name",
                                    "学生域名",
                                    "hostname",
                                    host,
                                    "platform",
                                    "student",
                                    "certificateRef",
                                    "zx-student-tls",
                                    "status",
                                    "DISABLED"));
            var dr =
                    mvc.perform(
                                    post(domains)
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(json.writeValueAsString(domain)))
                            .andExpect(status().isCreated())
                            .andExpect(
                                    jsonPath("$.hostname")
                                            .value(host.toLowerCase(java.util.Locale.ROOT)))
                            .andReturn()
                            .getResponse();
            domain.put("hostname", host.toLowerCase(java.util.Locale.ROOT));
            mvc.perform(
                            post(domains)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(domain)))
                    .andExpect(status().isConflict());
            domain.put("hostname", "-bad.example.com");
            mvc.perform(
                            post(domains)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(domain)))
                    .andExpect(status().isUnprocessableEntity());
            mvc.perform(
                            get(domains)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("q", "学生域名")
                                    .param("status", "DISABLED"))
                    .andExpect(status().isOk());
            mvc.perform(
                            delete(
                                            domains
                                                    + "/"
                                                    + json.readTree(dr.getContentAsString())
                                                            .get("id")
                                                            .asText())
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\""))
                    .andExpect(status().isNoContent());
        } finally {
            db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", adminId, superRole);
        }
    }

    @Test
    void orderDraftScopeReassignmentAndReferences() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        try {
            UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID(), student = UUID.randomUUID();
            String t1 = UUID.randomUUID().toString(),
                    t2 = UUID.randomUUID().toString(),
                    ts = UUID.randomUUID().toString();
            seed(p1, "partner", t1);
            seed(p2, "partner", t2);
            seed(student, "student", ts);
            for (UUID p : List.of(p1, p2)) {
                db.update(
                        "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000003')",
                        p);
                db.update("UPDATE user_account SET activated_at=now() WHERE id=?", p);
            }
            db.update(
                    "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')",
                    student);
            UUID school = UUID.randomUUID(), major = UUID.randomUUID();
            db.update(
                    "INSERT INTO school(id,name,code,region,status,created_by,updated_by)"
                            + " VALUES(?,'订单院校',?,'浙江','ENABLED',?,?)",
                    school,
                    school.toString(),
                    adminId,
                    adminId);
            db.update(
                    "INSERT INTO major(id,name,code,school_id,level,status,created_by,updated_by)"
                            + " VALUES(?,'订单专业',?,?,'BACHELOR','ENABLED',?,?)",
                    major,
                    major.toString(),
                    school,
                    adminId,
                    adminId);
            String base = "/api/v1/admin/orders", number = "ZX-" + UUID.randomUUID();
            var body =
                    new HashMap<String, Object>(
                            Map.of(
                                    "title",
                                    "隔离订单",
                                    "orderNo",
                                    number,
                                    "studentPhone",
                                    "13812340000",
                                    "partnerId",
                                    p1,
                                    "amount",
                                    "1999.123456",
                                    "currency",
                                    "CNY",
                                    "packageCode",
                                    "STANDARD",
                                    "source",
                                    "MANUAL",
                                    "schoolId",
                                    school));
            body.put("majorId", major);
            var response =
                    mvc.perform(
                                    post(base)
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content(json.writeValueAsString(body)))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.status").value("DRAFT"))
                            .andExpect(jsonPath("$.studentPhone").doesNotExist())
                            .andReturn()
                            .getResponse();
            String id = json.readTree(response.getContentAsString()).get("id").asText();
            assertFalse(
                    db.queryForObject(
                                    "SELECT student_phone_ciphertext FROM software_order WHERE"
                                            + " id=?",
                                    String.class,
                                    UUID.fromString(id))
                            .contains("13812340000"));
            mvc.perform(get("/api/v1/partner/orders/" + id).header("Authorization", "Bearer " + t1))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/partner/orders/" + id).header("Authorization", "Bearer " + t2))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/student/orders/" + id).header("Authorization", "Bearer " + ts))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            get("/api/v1/partner/orders")
                                    .header("Authorization", "Bearer " + t2)
                                    .param("q", number))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            String patch =
                    json.writeValueAsString(
                            Map.of("schoolId", school, "majorId", major, "note", "更新备注"));
            mvc.perform(
                            patch("/api/v1/partner/orders/" + id)
                                    .header("Authorization", "Bearer " + t2)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content(patch))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            patch("/api/v1/partner/orders/" + id)
                                    .header("Authorization", "Bearer " + t1)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content(patch))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(2));
            mvc.perform(
                            post(base + "/" + id + "/assignment")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"2\"")
                                    .contentType("application/json")
                                    .content(
                                            json.writeValueAsString(
                                                    Map.of("partnerId", p2, "reason", "业务移交"))))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/partner/orders/" + id).header("Authorization", "Bearer " + t1))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/partner/orders/" + id).header("Authorization", "Bearer " + t2))
                    .andExpect(status().isOk());
            mvc.perform(
                            delete("/api/v1/admin/majors/" + major)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\""))
                    .andExpect(status().isConflict());
            var partnerBody =
                    Map.of(
                            "title",
                            "中介下单",
                            "studentPhone",
                            "13812340001",
                            "schoolId",
                            school,
                            "majorId",
                            major,
                            "packageCode",
                            "STANDARD",
                            "note",
                            "待报价");
            mvc.perform(
                            post("/api/v1/partner/orders")
                                    .header("Authorization", "Bearer " + t1)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(partnerBody)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.partnerId").value(p1.toString()))
                    .andExpect(jsonPath("$.amount").value("0.000000"));
        } finally {
            db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", adminId, superRole);
        }
    }

    @Test
    void orderBindingProofFailureCommitAndConcurrentConsumption() throws Exception {
        UUID student = UUID.randomUUID(), other = UUID.randomUUID();
        String token = UUID.randomUUID().toString(),
                otherToken = UUID.randomUUID().toString(),
                phone = "13900123456";
        seed(student, "student", token);
        seed(other, "student", otherToken);
        for (UUID user : List.of(student, other))
            db.update(
                    "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')", user);
        db.update(
                "UPDATE user_account SET phone_digest=?,phone_verified_at=now(),activated_at=now()"
                        + " WHERE id=?",
                digester.digest("phone", phone),
                student);
        UUID school = UUID.randomUUID(), major = UUID.randomUUID(), order = UUID.randomUUID();
        String number = "BIND-" + UUID.randomUUID();
        db.update(
                "INSERT INTO school(id,name,code,region,status,created_by,updated_by)"
                        + " VALUES(?,'绑定院校',?,'浙江','ENABLED',?,?)",
                school,
                school.toString(),
                adminId,
                adminId);
        db.update(
                "INSERT INTO major(id,name,code,school_id,level,status,created_by,updated_by)"
                        + " VALUES(?,'绑定专业',?,?,'BACHELOR','ENABLED',?,?)",
                major,
                major.toString(),
                school,
                adminId,
                adminId);
        // 隔离状态机夹具，仅测试绑定；生产确认/收款流程仍需另外验收。
        db.update(
                "INSERT INTO"
                    + " software_order(id,order_no,title,student_phone_digest,student_phone_ciphertext,amount,currency,package_code,source,school_id,major_id,status,created_by,updated_by)"
                    + " VALUES(?,?,'绑定订单',?,'isolated-cipher-placeholder',100,'CNY','STANDARD','MANUAL',?,?,'CONFIRMED',?,?)",
                order,
                number,
                digester.digest("phone", phone),
                school,
                major,
                adminId,
                adminId);
        var codes = new java.util.concurrent.ConcurrentHashMap<UUID, String>();
        org.mockito.Mockito.doAnswer(
                        call -> {
                            codes.put(call.getArgument(0), call.getArgument(2));
                            return null;
                        })
                .when(sms)
                .send(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        String challengeBody =
                json.writeValueAsString(
                        Map.of("phone", phone, "purpose", "BIND_ORDER", "orderNo", number));
        mvc.perform(
                        post("/api/v1/auth/challenges")
                                .contentType("application/json")
                                .content(challengeBody))
                .andExpect(status().isUnauthorized());
        String first =
                mvc.perform(
                                post("/api/v1/auth/challenges")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType("application/json")
                                        .content(challengeBody))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID challenge = UUID.fromString(json.readTree(first).get("challengeId").asText());
        String path = "/api/v1/student/order-bindings";
        String bad = codes.get(challenge).equals("999999") ? "888888" : "999999";
        for (int n = 0; n < 5; n++)
            mvc.perform(
                            post(path)
                                    .header("Authorization", "Bearer " + token)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "orderNo",
                                                            number,
                                                            "challengeId",
                                                            challenge,
                                                            "code",
                                                            bad))))
                    .andExpect(status().isUnauthorized());
        assertEquals(
                5,
                db.queryForObject(
                        "SELECT attempts FROM order_binding_challenge WHERE id=?",
                        Integer.class,
                        challenge));
        String failureKey = UUID.randomUUID().toString();
        String failedBody =
                json.writeValueAsString(
                        Map.of("orderNo", number, "challengeId", challenge, "code", bad));
        for (int n = 0; n < 2; n++)
            mvc.perform(
                            post(path)
                                    .header("Authorization", "Bearer " + token)
                                    .header("Idempotency-Key", failureKey)
                                    .contentType("application/json")
                                    .content(failedBody))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "orderNo",
                                                        number,
                                                        "challengeId",
                                                        challenge,
                                                        "code",
                                                        codes.get(challenge)))))
                .andExpect(status().isUnauthorized());
        String second =
                mvc.perform(
                                post("/api/v1/auth/challenges")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType("application/json")
                                        .content(challengeBody))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID fresh = UUID.fromString(json.readTree(second).get("challengeId").asText());
        String binding =
                json.writeValueAsString(
                        Map.of("orderNo", number, "challengeId", fresh, "code", codes.get(fresh)));
        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer " + otherToken)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(binding))
                .andExpect(status().isUnauthorized());
        var start = new java.util.concurrent.CountDownLatch(1);
        String key = UUID.randomUUID().toString();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> call =
                    () -> {
                        start.await();
                        return mvc.perform(
                                        post(path)
                                                .header("Authorization", "Bearer " + token)
                                                .header("Idempotency-Key", key)
                                                .contentType("application/json")
                                                .content(binding))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var a = pool.submit(call);
            var b = pool.submit(call);
            start.countDown();
            assertEquals(200, a.get());
            assertEquals(200, b.get());
        }
        assertEquals(
                student,
                db.queryForObject(
                        "SELECT student_id FROM software_order WHERE id=?", UUID.class, order));
        assertEquals(
                2L,
                db.queryForObject(
                        "SELECT version FROM software_order WHERE id=?", Long.class, order));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE resource_id=? AND action='BOUND'",
                        Integer.class,
                        order));
        assertEquals(
                "CONSUMED",
                db.queryForObject(
                        "SELECT state FROM order_binding_challenge WHERE id=?",
                        String.class,
                        fresh));
        mvc.perform(
                        get("/api/v1/student/orders/" + order)
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(binding))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void statisticsUseCurrentOwnershipAndBeijingDateBoundaries() throws Exception {
        UUID student = UUID.randomUUID(),
                partner = UUID.randomUUID(),
                school = UUID.randomUUID(),
                major = UUID.randomUUID(),
                order = UUID.randomUUID();
        String st = UUID.randomUUID().toString(), pt = UUID.randomUUID().toString();
        seed(student, "student", st);
        seed(partner, "partner", pt);
        db.update(
                "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000001')", adminId);
        db.update(
                "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')", student);
        UUID partnerRole = UUID.randomUUID();
        db.update(
                "INSERT INTO role(id,code,name,platform,data_scope,status)"
                        + " VALUES(?,?,?,'partner','OWNED','ENABLED')",
                partnerRole,
                partnerRole.toString(),
                "统计测试中介");
        db.update("INSERT INTO user_role VALUES(?,?)", partner, partnerRole);
        db.update("INSERT INTO role_permission VALUES(?,'get_partner_statistics')", partnerRole);
        try {
            db.update(
                    "INSERT INTO school(id,name,code,region,status,created_by,updated_by)"
                            + " VALUES(?,'统计院校',?,'浙江','ENABLED',?,?)",
                    school,
                    school.toString(),
                    adminId,
                    adminId);
            db.update(
                    "INSERT INTO major(id,name,code,school_id,level,status,created_by,updated_by)"
                            + " VALUES(?,'统计专业',?,?,'BACHELOR','ENABLED',?,?)",
                    major,
                    major.toString(),
                    school,
                    adminId,
                    adminId);
            db.update(
                    "INSERT INTO"
                        + " software_order(id,order_no,title,student_id,partner_id,student_phone_digest,student_phone_ciphertext,amount,currency,package_code,source,school_id,major_id,status,created_by,updated_by,created_at)"
                        + " VALUES(?,?,'统计订单',?,?,?,'test-only',123.456789,'CNY','STANDARD','MANUAL',?,?,'DRAFT',?,?,'2020-02-01T16:00:00Z')",
                    order,
                    order.toString(),
                    student,
                    partner,
                    UUID.randomUUID().toString(),
                    school,
                    major,
                    adminId,
                    adminId);
            for (int n = 0; n < 2; n++)
                db.update(
                        "INSERT INTO"
                            + " audit_event(id,actor_id,action,resource_type,resource_id,created_at)"
                            + " VALUES(?,?,'CREATED','forum_post',?,'2020-02-01T16:00:00Z')",
                        UUID.randomUUID(),
                        student,
                        UUID.randomUUID());
            var view =
                    json.readTree(
                            mvc.perform(
                                            get("/api/v1/student/statistics")
                                                    .header("Authorization", "Bearer " + st)
                                                    .param("from", "2020-02-02")
                                                    .param("to", "2020-02-02"))
                                    .andExpect(status().isOk())
                                    .andExpect(header().string("Cache-Control", "no-store"))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            var values = new HashMap<String, String>();
            view.get("items")
                    .forEach(
                            x -> {
                                values.put(x.get("metric").asText(), x.get("value").asText());
                                assertEquals("2020-02-02", x.get("bucket").asText());
                            });
            assertEquals("1", values.get("orders_created"));
            assertEquals("123.456789", values.get("orders_face_amount"));
            assertEquals("1", values.get("active_students"));
            assertFalse(values.containsKey("receipts_confirmed_amount"));
            mvc.perform(
                            get("/api/v1/student/statistics")
                                    .header("Authorization", "Bearer " + st)
                                    .param("from", "2020-02-01")
                                    .param("to", "2020-02-01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty());
            var pv =
                    json.readTree(
                            mvc.perform(
                                            get("/api/v1/partner/statistics")
                                                    .header("Authorization", "Bearer " + pt)
                                                    .param("from", "2020-02-02")
                                                    .param("to", "2020-02-02"))
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertEquals(2, pv.get("items").size());
            db.update("UPDATE software_order SET partner_id=NULL WHERE id=?", order);
            mvc.perform(
                            get("/api/v1/partner/statistics")
                                    .header("Authorization", "Bearer " + pt)
                                    .param("from", "2020-02-02")
                                    .param("to", "2020-02-02"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty());
            mvc.perform(
                            get("/api/v1/admin/statistics")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("from", "2020-02-02")
                                    .param("to", "2020-02-02"))
                    .andExpect(status().isOk());
            mvc.perform(
                            get("/api/v1/admin/statistics")
                                    .header("Authorization", "Bearer " + st)
                                    .param("from", "2020-02-02")
                                    .param("to", "2020-02-02"))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get("/api/v1/student/statistics")
                                    .header("Authorization", "Bearer " + st)
                                    .param("from", "2020-02-02")
                                    .param("to", "2020-02-01"))
                    .andExpect(status().isBadRequest());
            mvc.perform(
                            get("/api/v1/student/statistics")
                                    .header("Authorization", "Bearer " + st)
                                    .param("from", "2020-01-01")
                                    .param("to", "2022-01-01"))
                    .andExpect(status().isBadRequest());
            // 报告以真实私有对象交付；下载时必须重新检查完整归属集合。
            for (String permission :
                    List.of(
                            "get_partner_orders",
                            "post_partner_exports",
                            "get_partner_jobs_id",
                            "post_partner_jobs_id_download"))
                db.update("INSERT INTO role_permission VALUES(?,?)", partnerRole, permission);
            db.update("UPDATE software_order SET partner_id=? WHERE id=?", partner, order);
            db.update(
                    "INSERT INTO"
                        + " storage_config(id,name,provider,bucket,region,endpoint,credential_ref,access,status,created_by,updated_by)"
                        + " VALUES(?,'隔离上传','S3_COMPATIBLE','zx-quarantine-private',?,?,'local-minio','PRIVATE','ENABLED',?,?)"
                        + " ON CONFLICT(id) DO NOTHING",
                    UPLOAD_STORE,
                    System.getenv("ZX_S3_REGION"),
                    System.getenv("ZX_S3_ENDPOINT"),
                    adminId,
                    adminId);
            String request = "{\"kind\":\"ORDERS\",\"from\":\"2020-02-02\",\"to\":\"2020-02-02\"}",
                    key = UUID.randomUUID().toString();
            String job =
                    json.readTree(
                                    mvc.perform(
                                                    post("/api/v1/partner/exports")
                                                            .header("Authorization", "Bearer " + pt)
                                                            .header("Idempotency-Key", key)
                                                            .contentType("application/json")
                                                            .content(request))
                                            .andExpect(status().isAccepted())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                            .get("id")
                            .asText();
            mvc.perform(
                            post("/api/v1/partner/exports")
                                    .header("Authorization", "Bearer " + pt)
                                    .header("Idempotency-Key", key)
                                    .contentType("application/json")
                                    .content(request))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.id").value(job));
            String download = "/api/v1/partner/jobs/" + job + "/download";
            mvc.perform(post(download).header("Authorization", "Bearer " + pt))
                    .andExpect(status().isUnprocessableEntity());
            assertTrue(exports.runOne());
            assertFalse(exports.runOne());
            mvc.perform(get("/api/v1/partner/jobs/" + job).header("Authorization", "Bearer " + pt))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("SUCCEEDED"));
            var signed =
                    json.readTree(
                            mvc.perform(post(download).header("Authorization", "Bearer " + pt))
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            var http = java.net.http.HttpClient.newHttpClient();
            String csv =
                    http.send(
                                    java.net.http.HttpRequest.newBuilder(
                                                    java.net.URI.create(signed.get("url").asText()))
                                            .GET()
                                            .build(),
                                    java.net.http.HttpResponse.BodyHandlers.ofString())
                            .body();
            assertTrue(csv.contains("123.456789"));
            assertTrue(csv.contains("统计订单"));
            assertFalse(csv.contains("student_phone"));
            db.update("UPDATE software_order SET partner_id=NULL WHERE id=?", order);
            mvc.perform(post(download).header("Authorization", "Bearer " + pt))
                    .andExpect(status().isForbidden());
            db.update("UPDATE software_order SET partner_id=? WHERE id=?", partner, order);
            db.update(
                    "DELETE FROM role_permission WHERE role_id=? AND"
                            + " permission_code='get_partner_orders'",
                    partnerRole);
            mvc.perform(post(download).header("Authorization", "Bearer " + pt))
                    .andExpect(status().isForbidden());
            db.update("INSERT INTO role_permission VALUES(?,'get_partner_orders')", partnerRole);
            mvc.perform(
                            post("/api/v1/partner/exports")
                                    .header("Authorization", "Bearer " + pt)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(request.replace("ORDERS", "USAGE")))
                    .andExpect(status().isForbidden());
            db.update(
                    "UPDATE export_job SET result_expires_at=now()-interval '1 second' WHERE id=?",
                    UUID.fromString(job));
            db.update(
                    "UPDATE export_attempt SET created_at=now()-interval '2 hours' WHERE job_id=?",
                    UUID.fromString(job));
            assertTrue(exportCleanup.runOne());
            assertFalse(exportCleanup.runOne());
            mvc.perform(post(download).header("Authorization", "Bearer " + pt))
                    .andExpect(status().isUnprocessableEntity());
            var report =
                    db.queryForMap(
                            "SELECT object_key,object_version FROM export_job WHERE id=?",
                            UUID.fromString(job));
            var target =
                    new cn.zhixiang.platform.storage.port.ObjectStorage.Target(
                            "S3_COMPATIBLE",
                            System.getenv("ZX_S3_ENDPOINT"),
                            System.getenv("ZX_S3_REGION"),
                            "zx-quarantine-private",
                            "local-minio");
            assertThrows(
                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                    () -> storage.stat(target, (String) report.get("object_key")));
            for (String kind : List.of("PROJECTS", "USAGE", "AUDIT")) {
                String adminJob =
                        json.readTree(
                                        mvc.perform(
                                                        post("/api/v1/admin/exports")
                                                                .header(
                                                                        "Authorization",
                                                                        "Bearer " + adminToken)
                                                                .header(
                                                                        "Idempotency-Key",
                                                                        UUID.randomUUID())
                                                                .contentType("application/json")
                                                                .content(
                                                                        request.replace(
                                                                                "ORDERS", kind)))
                                                .andExpect(status().isAccepted())
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString())
                                .get("id")
                                .asText();
                assertTrue(exports.runOne());
                mvc.perform(
                                get("/api/v1/admin/jobs/" + adminJob)
                                        .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.state").value("SUCCEEDED"));
                mvc.perform(
                                get("/api/v1/partner/jobs/" + adminJob)
                                        .header("Authorization", "Bearer " + pt))
                        .andExpect(status().isNotFound());
                mvc.perform(
                                post("/api/v1/admin/jobs/" + adminJob + "/download")
                                        .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk());
                db.update(
                        "UPDATE export_job SET result_expires_at=now()-interval '1 second' WHERE"
                                + " id=?",
                        UUID.fromString(adminJob));
                db.update(
                        "UPDATE export_attempt SET created_at=now()-interval '2 hours' WHERE"
                                + " job_id=?",
                        UUID.fromString(adminJob));
                assertTrue(exportCleanup.runOne());
            }
            String revoked =
                    json.readTree(
                                    mvc.perform(
                                                    post("/api/v1/partner/exports")
                                                            .header("Authorization", "Bearer " + pt)
                                                            .header(
                                                                    "Idempotency-Key",
                                                                    UUID.randomUUID())
                                                            .contentType("application/json")
                                                            .content(request))
                                            .andExpect(status().isAccepted())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                            .get("id")
                            .asText();
            db.update(
                    "DELETE FROM role_permission WHERE role_id=? AND"
                            + " permission_code='get_partner_orders'",
                    partnerRole);
            assertTrue(exports.runOne());
            mvc.perform(
                            get("/api/v1/partner/jobs/" + revoked)
                                    .header("Authorization", "Bearer " + pt))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("FAILED"))
                    .andExpect(jsonPath("$.errorCode").value("EXPORT_SCOPE_REVOKED"));
            db.update(
                    "UPDATE export_attempt SET created_at=now()-interval '2 hours' WHERE job_id=?",
                    UUID.fromString(revoked));
            assertTrue(exportCleanup.runOne());
            db.update("INSERT INTO role_permission VALUES(?,'get_partner_orders')", partnerRole);
            String recovered =
                    json.readTree(
                                    mvc.perform(
                                                    post("/api/v1/partner/exports")
                                                            .header("Authorization", "Bearer " + pt)
                                                            .header(
                                                                    "Idempotency-Key",
                                                                    UUID.randomUUID())
                                                            .contentType("application/json")
                                                            .content(request))
                                            .andExpect(status().isAccepted())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                            .get("id")
                            .asText();
            db.update(
                    "UPDATE export_job SET"
                        + " state='RUNNING',generation=1,attempts=1,lease_until=now()-interval '1"
                        + " second' WHERE id=?",
                    UUID.fromString(recovered));
            db.update(
                    "INSERT INTO export_attempt(job_id,generation,object_key,created_at)"
                            + " VALUES(?,1,?,now()-interval '2 hours')",
                    UUID.fromString(recovered),
                    "exports/" + recovered + "/1");
            assertTrue(exports.runOne());
            assertEquals(
                    2L,
                    db.queryForObject(
                            "SELECT generation FROM export_job WHERE id=?",
                            Long.class,
                            UUID.fromString(recovered)));
            assertTrue(exportCleanup.runOne());
            assertEquals(
                    0,
                    exportMapper.finish(
                            UUID.fromString(recovered), 1, "exports/" + recovered + "/1", null));
            String lateKey = "exports/" + recovered + "/1";
            storage.write(
                    target,
                    lateKey,
                    "late".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "text/csv");
            db.update(
                    "UPDATE export_attempt SET cleaned_at=now()-interval '2 days' WHERE job_id=?"
                            + " AND generation=1",
                    UUID.fromString(recovered));
            assertTrue(exportCleanup.runOne());
            assertThrows(
                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                    () -> storage.stat(target, lateKey));

            mvc.perform(
                            post("/api/v1/partner/jobs/" + recovered + "/download")
                                    .header("Authorization", "Bearer " + pt))
                    .andExpect(status().isOk());
            db.update(
                    "UPDATE export_job SET result_expires_at=now()-interval '1 second' WHERE id=?",
                    UUID.fromString(recovered));
            db.update(
                    "UPDATE export_attempt SET created_at=now()-interval '2 hours' WHERE job_id=?",
                    UUID.fromString(recovered));
            assertTrue(exportCleanup.runOne());
            assertFalse(exportCleanup.runOne());

        } finally {
            db.update(
                    "DELETE FROM user_role WHERE user_id=? AND"
                            + " role_id='00000000-0000-4000-8000-000000000001'",
                    adminId);
        }
    }

    @Test
    void auditPaginationDoesNotExposeChatMetadataWithoutExtraPermission() throws Exception {
        UUID auditor = UUID.randomUUID(), role = UUID.randomUUID(), target = UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        seed(auditor, "admin", token);
        db.update(
                "INSERT INTO role(id,code,name,platform,data_scope,status)"
                        + " VALUES(?,?,?,'admin','ALL','ENABLED')",
                role,
                role.toString(),
                "只读审计");
        db.update("INSERT INTO user_role VALUES(?,?)", auditor, role);
        db.update("INSERT INTO role_permission VALUES(?,'get_admin_audits')", role);
        db.update(
                "INSERT INTO audit_event(id,actor_id,action,resource_type,resource_id,detail)"
                    + " VALUES(?,?,'TEST_AUDIT','school',?,'{\"reason\":\"测试依据\",\"secret\":\"NEVER_RETURN\"}')",
                UUID.randomUUID(),
                adminId,
                target);
        db.update(
                "INSERT INTO audit_event(id,actor_id,action,resource_type,resource_id,detail)"
                        + " VALUES(?,?,'CHAT_MESSAGES_READ','conversation',?,'{}')",
                UUID.randomUUID(),
                adminId,
                target);
        String path = "/api/v1/admin/audits";
        var response =
                mvc.perform(
                                get(path)
                                        .header("Authorization", "Bearer " + token)
                                        .param("q", target.toString())
                                        .param("size", "1")
                                        .param("sort", "createdAt:asc"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.total").value(1))
                        .andExpect(jsonPath("$.items[0].resourceType").value("school"))
                        .andExpect(jsonPath("$.items[0].traceId").isEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertFalse(response.contains("NEVER_RETURN"));
        mvc.perform(
                        get(path)
                                .header("Authorization", "Bearer " + token)
                                .param("q", target.toString())
                                .param("page", "2")
                                .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(
                        get(path)
                                .header("Authorization", "Bearer " + token)
                                .param("sort", "updatedAt:desc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path).header("Authorization", "Bearer " + token).param("status", "ENABLED"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path).header("Authorization", "Bearer " + partnerToken))
                .andExpect(status().isForbidden());
        db.update("INSERT INTO role_permission VALUES(?,'chat:audit')", role);
        mvc.perform(
                        get(path)
                                .header("Authorization", "Bearer " + token)
                                .param("q", target.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void studentForumUploadRequiresOwnershipScanAndPublishedAssociation() throws Exception {
        db.update(
                "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000001')", adminId);
        UUID author = UUID.randomUUID(), other = UUID.randomUUID(), board = UUID.randomUUID();
        String st = UUID.randomUUID().toString(), ot = UUID.randomUUID().toString();
        seed(author, "student", st);
        seed(other, "student", ot);
        for (UUID u : List.of(author, other))
            db.update("INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')", u);
        db.update(
                "INSERT INTO"
                    + " forum_board(id,name,code,requires_review,sort_order,status,created_by,updated_by)"
                    + " VALUES(?,'附件测试',?,true,0,'ENABLED',?,?)",
                board,
                board.toString(),
                adminId,
                adminId);
        db.update(
                "INSERT INTO"
                    + " storage_config(id,name,provider,bucket,region,endpoint,credential_ref,access,status,created_by,updated_by)"
                    + " VALUES(?,'隔离上传','S3_COMPATIBLE','zx-quarantine-private',?,?,'local-minio','PRIVATE','ENABLED',?,?)"
                    + " ON CONFLICT(id) DO NOTHING",
                UPLOAD_STORE,
                System.getenv("ZX_S3_REGION"),
                System.getenv("ZX_S3_ENDPOINT"),
                adminId,
                adminId);
        var target =
                new cn.zhixiang.platform.storage.port.ObjectStorage.Target(
                        "S3_COMPATIBLE",
                        System.getenv("ZX_S3_ENDPOINT"),
                        System.getenv("ZX_S3_REGION"),
                        "zx-quarantine-private",
                        "local-minio");
        UUID asset = null;
        String objectKey = null, objectVersion = null;
        try {
            var body =
                    new HashMap<String, Object>(
                            Map.of(
                                    "boardId",
                                    board,
                                    "title",
                                    "附件问题",
                                    "body",
                                    "请查看附件",
                                    "tagIds",
                                    List.of(),
                                    "assetIds",
                                    List.of()));
            String id =
                    json.readTree(
                                    mvc.perform(
                                                    post("/api/v1/student/posts")
                                                            .header("Authorization", "Bearer " + st)
                                                            .header(
                                                                    "Idempotency-Key",
                                                                    UUID.randomUUID())
                                                            .contentType("application/json")
                                                            .content(json.writeValueAsString(body)))
                                            .andExpect(status().isCreated())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                            .get("id")
                            .asText();
            byte[] bytes = "学生隔离附件".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String sha =
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(bytes));
            var input =
                    new HashMap<String, Object>(
                            Map.of(
                                    "ownerType",
                                    "FORUM",
                                    "ownerId",
                                    id,
                                    "filename",
                                    "说明.txt",
                                    "size",
                                    bytes.length,
                                    "sha256",
                                    sha,
                                    "mimeType",
                                    "text/plain"));
            mvc.perform(
                            post("/api/v1/student/uploads")
                                    .header("Authorization", "Bearer " + ot)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(input)))
                    .andExpect(status().isNotFound());
            input.put("ownerType", "PROJECT");
            mvc.perform(
                            post("/api/v1/student/uploads")
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(input)))
                    .andExpect(status().isNotFound());
            input.put("ownerType", "FORUM");
            var grant =
                    json.readTree(
                            mvc.perform(
                                            post("/api/v1/student/uploads")
                                                    .header("Authorization", "Bearer " + st)
                                                    .header("Idempotency-Key", UUID.randomUUID())
                                                    .contentType("application/json")
                                                    .content(json.writeValueAsString(input)))
                                    .andExpect(status().isCreated())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            asset = UUID.fromString(grant.get("assetId").asText());
            objectKey = grant.get("objectKey").asText();
            body.put("assetIds", List.of(asset));
            mvc.perform(
                            put("/api/v1/student/posts/" + id)
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isUnprocessableEntity());
            var client = java.net.http.HttpClient.newHttpClient();
            var req =
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create(grant.get("url").asText()))
                            .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes));
            grant.get("headers")
                    .properties()
                    .forEach(
                            e -> {
                                if (!e.getKey().equalsIgnoreCase("Content-Length"))
                                    req.header(e.getKey(), e.getValue().asText());
                            });
            assertEquals(
                    200,
                    client.send(req.build(), java.net.http.HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            objectVersion = storage.stat(target, objectKey).versionId();
            String complete =
                    "/api/v1/student/uploads/" + grant.get("uploadId").asText() + "/complete";
            mvc.perform(
                            post(complete)
                                    .header("Authorization", "Bearer " + ot)
                                    .header("Idempotency-Key", UUID.randomUUID()))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            post(complete)
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID()))
                    .andExpect(status().isAccepted());
            org.mockito.Mockito.doReturn(true)
                    .when(scanner)
                    .clean(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyLong());
            assertTrue(validation.runOne());
            String download = "/api/v1/student/assets/" + asset + "/download";
            mvc.perform(post(download).header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk());
            mvc.perform(post(download).header("Authorization", "Bearer " + ot))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            put("/api/v1/student/posts/" + id)
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assetIds[0]").value(asset.toString()));
            forumModeration(id, 2, "APPROVE").andExpect(status().isOk());
            var signed =
                    json.readTree(
                            mvc.perform(post(download).header("Authorization", "Bearer " + ot))
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertArrayEquals(
                    bytes,
                    client.send(
                                    java.net.http.HttpRequest.newBuilder(
                                                    java.net.URI.create(signed.get("url").asText()))
                                            .GET()
                                            .build(),
                                    java.net.http.HttpResponse.BodyHandlers.ofByteArray())
                            .body());
            mvc.perform(
                            delete("/api/v1/admin/assets/" + asset)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"3\""))
                    .andExpect(status().isConflict());
            forumModeration(id, 3, "HIDE").andExpect(status().isOk());
            mvc.perform(post(download).header("Authorization", "Bearer " + ot))
                    .andExpect(status().isNotFound());
            mvc.perform(post(download).header("Authorization", "Bearer " + st))
                    .andExpect(status().isNotFound());
            forumModeration(id, 4, "APPROVE").andExpect(status().isOk());
            body.put("assetIds", List.of());
            mvc.perform(
                            put("/api/v1/student/posts/" + id)
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"5\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isOk());
            forumModeration(id, 6, "APPROVE").andExpect(status().isOk());
            mvc.perform(post(download).header("Authorization", "Bearer " + ot))
                    .andExpect(status().isNotFound());
            mvc.perform(post(download).header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk());
            String otherPost =
                    json.readTree(
                                    mvc.perform(
                                                    post("/api/v1/student/posts")
                                                            .header("Authorization", "Bearer " + ot)
                                                            .header(
                                                                    "Idempotency-Key",
                                                                    UUID.randomUUID())
                                                            .contentType("application/json")
                                                            .content(json.writeValueAsString(body)))
                                            .andExpect(status().isCreated())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                            .get("id")
                            .asText();
            body.put("assetIds", List.of(asset));
            mvc.perform(
                            put("/api/v1/student/posts/" + otherPost)
                                    .header("Authorization", "Bearer " + ot)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isUnprocessableEntity());
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM forum_post_asset WHERE post_id=?",
                            Integer.class,
                            UUID.fromString(otherPost)));
            // 两个不同账户并发关联和删除同一资产，帖子锁必须使二者形成单一先后顺序。
            String attachmentBody = json.writeValueAsString(body), assetId = asset.toString();
            var gate = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var attach =
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return mvc.perform(
                                                    put("/api/v1/student/posts/" + id)
                                                            .header("Authorization", "Bearer " + st)
                                                            .header(
                                                                    "Idempotency-Key",
                                                                    UUID.randomUUID())
                                                            .header("If-Match", "\"7\"")
                                                            .contentType("application/json")
                                                            .content(attachmentBody))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                });
                var remove =
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return mvc.perform(
                                                    delete("/api/v1/admin/assets/" + assetId)
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + adminToken)
                                                            .header(
                                                                    "Idempotency-Key",
                                                                    UUID.randomUUID())
                                                            .header("If-Match", "\"3\""))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                });
                gate.countDown();
                int a = attach.get(), d = remove.get();
                assertTrue(
                        (a == 200 && d == 409) || (a == 422 && d == 204),
                        "关联/删除必须互斥，实际=" + a + "/" + d);
                assertEquals(
                        0,
                        db.queryForObject(
                                "SELECT count(*) FROM forum_post_asset f JOIN asset a ON"
                                    + " a.id=f.asset_id WHERE a.id=? AND a.deleted_at IS NOT NULL",
                                Integer.class,
                                UUID.fromString(assetId)));
            }

        } finally {
            if (asset != null) {
                var row =
                        db.queryForMap(
                                "SELECT private_key,object_version FROM asset WHERE id=?", asset);
                if (row.get("private_key") != null)
                    storage.remove(
                            target,
                            (String) row.get("private_key"),
                            (String) row.get("object_version"));
            }
            if (objectKey != null && objectVersion != null)
                storage.remove(target, objectKey, objectVersion);
            db.update(
                    "DELETE FROM user_role WHERE user_id=? AND"
                            + " role_id='00000000-0000-4000-8000-000000000001'",
                    adminId);
        }
    }

    @Test
    void assetQuarantineScanDownloadAndDeletion() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        UUID school = UUID.randomUUID(), major = UUID.randomUUID(), order = UUID.randomUUID();
        db.update(
                "INSERT INTO school(id,name,code,region,status,created_by,updated_by)"
                        + " VALUES(?,'资产院校',?,'浙江','ENABLED',?,?)",
                school,
                school.toString(),
                adminId,
                adminId);
        db.update(
                "INSERT INTO major(id,name,code,school_id,level,status,created_by,updated_by)"
                        + " VALUES(?,'资产专业',?,?,'BACHELOR','ENABLED',?,?)",
                major,
                major.toString(),
                school,
                adminId,
                adminId);
        db.update(
                "INSERT INTO"
                    + " software_order(id,order_no,title,student_phone_digest,student_phone_ciphertext,amount,currency,package_code,source,school_id,major_id,status,created_by,updated_by)"
                    + " VALUES(?,?,'资产订单',?,'test-only',100,'CNY','STANDARD','MANUAL',?,?,'CONFIRMED',?,?)",
                order,
                order.toString(),
                UUID.randomUUID().toString(),
                school,
                major,
                adminId,
                adminId);
        db.update(
                "INSERT INTO"
                    + " storage_config(id,name,provider,bucket,region,endpoint,credential_ref,access,status,created_by,updated_by)"
                    + " VALUES(?,'隔离上传','S3_COMPATIBLE','zx-quarantine-private',?,?,'local-minio','PRIVATE','ENABLED',?,?)"
                    + " ON CONFLICT(id) DO NOTHING",
                UPLOAD_STORE,
                System.getenv("ZX_S3_REGION"),
                System.getenv("ZX_S3_ENDPOINT"),
                adminId,
                adminId);
        var target =
                new cn.zhixiang.platform.storage.port.ObjectStorage.Target(
                        "S3_COMPATIBLE",
                        System.getenv("ZX_S3_ENDPOINT"),
                        System.getenv("ZX_S3_REGION"),
                        "zx-quarantine-private",
                        "local-minio");
        byte[] bytes = "隔离资产校验".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha =
                HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var client = java.net.http.HttpClient.newHttpClient();
        try {
            for (boolean clean : List.of(true, false)) {
                String input =
                        json.writeValueAsString(
                                Map.of(
                                        "ownerType",
                                        "RECEIPT",
                                        "ownerId",
                                        order,
                                        "filename",
                                        "凭证.txt",
                                        "size",
                                        bytes.length,
                                        "sha256",
                                        sha,
                                        "mimeType",
                                        "text/plain"));
                var grant =
                        json.readTree(
                                mvc.perform(
                                                post("/api/v1/admin/uploads")
                                                        .header(
                                                                "Authorization",
                                                                "Bearer " + adminToken)
                                                        .header(
                                                                "Idempotency-Key",
                                                                UUID.randomUUID())
                                                        .contentType("application/json")
                                                        .content(input))
                                        .andExpect(status().isCreated())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString());
                UUID asset = UUID.fromString(grant.get("assetId").asText());
                String key = grant.get("objectKey").asText();
                var req =
                        java.net.http.HttpRequest.newBuilder(
                                        java.net.URI.create(grant.get("url").asText()))
                                .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes));
                grant.get("headers")
                        .properties()
                        .forEach(
                                e -> {
                                    if (!e.getKey().equalsIgnoreCase("Content-Length"))
                                        req.header(e.getKey(), e.getValue().asText());
                                });
                assertEquals(
                        200,
                        client.send(
                                        req.build(),
                                        java.net.http.HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                var source = storage.stat(target, key);
                try {
                    String
                            complete =
                                    "/api/v1/admin/uploads/"
                                            + grant.get("uploadId").asText()
                                            + "/complete",
                            idem = UUID.randomUUID().toString();
                    for (int n = 0; n < 2; n++)
                        mvc.perform(
                                        post(complete)
                                                .header("Authorization", "Bearer " + adminToken)
                                                .header("Idempotency-Key", idem))
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.state").value("QUARANTINED"));
                    assertEquals(
                            1,
                            db.queryForObject(
                                    "SELECT count(*) FROM asset_validation_task WHERE asset_id=?",
                                    Integer.class,
                                    asset));
                    var lease = assetTasks.claim();
                    assertNotNull(lease);
                    assertNull(assetTasks.claim());
                    db.update(
                            "UPDATE asset_validation_task SET lease_until=now()-interval '1 second'"
                                    + " WHERE asset_id=?",
                            asset);
                    var recovered = assetTasks.claim();
                    assertTrue(recovered.generation() > lease.generation());
                    assertEquals(0, assetTasks.finish(asset, lease.generation(), null));
                    assetTasks.retry(asset, recovered.generation(), "TEST_RECOVERY");
                    db.update(
                            "UPDATE asset_validation_task SET next_attempt_at=now() WHERE"
                                    + " asset_id=?",
                            asset);
                    String download = "/api/v1/admin/assets/" + asset + "/download";
                    mvc.perform(post(download).header("Authorization", "Bearer " + adminToken))
                            .andExpect(status().isUnprocessableEntity());
                    org.mockito.Mockito.when(
                                    scanner.clean(
                                            org.mockito.ArgumentMatchers.any(),
                                            org.mockito.ArgumentMatchers.anyLong()))
                            .thenThrow(
                                    new cn.zhixiang.platform.shared.exception.BusinessException(
                                            503, "SCANNER_UNAVAILABLE", "测试扫描不可用"));
                    assertTrue(validation.runOne());
                    assertEquals(
                            "QUARANTINED",
                            db.queryForObject(
                                    "SELECT state FROM asset WHERE id=?", String.class, asset));
                    db.update(
                            "UPDATE asset_validation_task SET"
                                + " state='FAILED',attempts=8,version=version+1 WHERE asset_id=?",
                            asset);
                    var job =
                            mvc.perform(
                                            get("/api/v1/admin/jobs/" + asset)
                                                    .header(
                                                            "Authorization",
                                                            "Bearer " + adminToken))
                                    .andExpect(status().isOk())
                                    .andExpect(jsonPath("$.state").value("FAILED"))
                                    .andReturn()
                                    .getResponse();
                    String retryPath = "/api/v1/admin/jobs/" + asset + "/retry",
                            retryKey = UUID.randomUUID().toString();
                    mvc.perform(
                                    get("/api/v1/admin/jobs")
                                            .header("Authorization", "Bearer " + adminToken)
                                            .param("q", asset.toString())
                                            .param("status", "FAILED"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.total").value(1));
                    mvc.perform(
                                    get("/api/v1/admin/jobs/" + asset)
                                            .header("Authorization", "Bearer " + partnerToken))
                            .andExpect(status().isForbidden());
                    for (int n = 0; n < 2; n++)
                        mvc.perform(
                                        post(retryPath)
                                                .header("Authorization", "Bearer " + adminToken)
                                                .header("Idempotency-Key", retryKey)
                                                .header("If-Match", job.getHeader("ETag"))
                                                .contentType("application/json")
                                                .content("{\"reason\":\"扫描器已恢复\"}"))
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.state").value("QUEUED"));
                    assertEquals(
                            1,
                            db.queryForObject(
                                    "SELECT count(*) FROM job_retry_event WHERE job_id=?",
                                    Integer.class,
                                    asset));
                    mvc.perform(
                                    post(retryPath)
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .header("If-Match", job.getHeader("ETag"))
                                            .contentType("application/json")
                                            .content("{\"reason\":\"旧版本\"}"))
                            .andExpect(status().isPreconditionFailed());
                    org.mockito.Mockito.doReturn(clean)
                            .when(scanner)
                            .clean(
                                    org.mockito.ArgumentMatchers.any(),
                                    org.mockito.ArgumentMatchers.anyLong());
                    assertTrue(validation.runOne());
                    mvc.perform(
                                    get("/api/v1/admin/assets/" + asset)
                                            .header("Authorization", "Bearer " + adminToken))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.state").value(clean ? "READY" : "REJECTED"));
                    mvc.perform(
                                    get("/api/v1/admin/assets/" + asset)
                                            .header("Authorization", "Bearer " + partnerToken))
                            .andExpect(status().isForbidden());
                    if (clean) {
                        var signed =
                                json.readTree(
                                        mvc.perform(
                                                        post(download)
                                                                .header(
                                                                        "Authorization",
                                                                        "Bearer " + adminToken))
                                                .andExpect(status().isOk())
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString());
                        assertArrayEquals(
                                bytes,
                                client.send(
                                                java.net.http.HttpRequest.newBuilder(
                                                                java.net.URI.create(
                                                                        signed.get("url").asText()))
                                                        .GET()
                                                        .build(),
                                                java.net.http.HttpResponse.BodyHandlers
                                                        .ofByteArray())
                                        .body());
                    }
                    if (clean) {
                        String receiptPath = "/api/v1/admin/orders/" + order + "/receipt",
                                receiptKey = UUID.randomUUID().toString();
                        var receipt =
                                new HashMap<String, Object>(
                                        Map.of(
                                                "reference",
                                                "TEST-" + UUID.randomUUID(),
                                                "receivedAt",
                                                java.time.OffsetDateTime.now()
                                                        .minusMinutes(1)
                                                        .toString(),
                                                "amount",
                                                "99",
                                                "proofAssetId",
                                                asset,
                                                "reason",
                                                "隔离测试人工核验"));
                        mvc.perform(
                                        post(receiptPath)
                                                .header("Authorization", "Bearer " + adminToken)
                                                .header("Idempotency-Key", UUID.randomUUID())
                                                .header("If-Match", "\"1\"")
                                                .contentType("application/json")
                                                .content(json.writeValueAsString(receipt)))
                                .andExpect(status().isUnprocessableEntity());
                        receipt.put("amount", "100.000000");
                        for (int n = 0; n < 2; n++)
                            mvc.perform(
                                            post(receiptPath)
                                                    .header("Authorization", "Bearer " + adminToken)
                                                    .header("Idempotency-Key", receiptKey)
                                                    .header("If-Match", "\"1\"")
                                                    .contentType("application/json")
                                                    .content(json.writeValueAsString(receipt)))
                                    .andExpect(status().isOk())
                                    .andExpect(jsonPath("$.receiptStatus").value("CONFIRMED"))
                                    .andExpect(header().string("ETag", "\"2\""));
                        assertEquals(
                                1,
                                db.queryForObject(
                                        "SELECT count(*) FROM order_receipt WHERE order_id=?",
                                        Integer.class,
                                        order));
                        mvc.perform(
                                        delete("/api/v1/admin/assets/" + asset)
                                                .header("Authorization", "Bearer " + adminToken)
                                                .header("Idempotency-Key", UUID.randomUUID())
                                                .header("If-Match", "\"3\""))
                                .andExpect(status().isConflict());
                        mvc.perform(
                                        post(receiptPath)
                                                .header("Authorization", "Bearer " + adminToken)
                                                .header("Idempotency-Key", UUID.randomUUID())
                                                .header("If-Match", "\"2\"")
                                                .contentType("application/json")
                                                .content(json.writeValueAsString(receipt)))
                                .andExpect(status().isUnprocessableEntity());
                    } else {
                        mvc.perform(
                                        delete("/api/v1/admin/assets/" + asset)
                                                .header("Authorization", "Bearer " + adminToken)
                                                .header("Idempotency-Key", UUID.randomUUID())
                                                .header("If-Match", "\"3\""))
                                .andExpect(status().isNoContent());
                        mvc.perform(post(download).header("Authorization", "Bearer " + adminToken))
                                .andExpect(status().isNotFound());
                    }
                } finally {
                    var row =
                            db.queryForMap(
                                    "SELECT private_key,object_version FROM asset WHERE id=?",
                                    asset);
                    if (row.get("private_key") != null)
                        storage.remove(
                                target,
                                (String) row.get("private_key"),
                                (String) row.get("object_version"));
                    storage.remove(target, key, source.versionId());
                }
            }
        } finally {
            db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", adminId, superRole);
        }
    }

    @Test
    void immutableDeliveryPublicationAndStudentScope() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        UUID school = UUID.randomUUID(),
                major = UUID.randomUUID(),
                order = UUID.randomUUID(),
                project = UUID.randomUUID(),
                student = UUID.randomUUID(),
                other = UUID.randomUUID(),
                store = UUID.randomUUID();
        String st = UUID.randomUUID().toString(), ot = UUID.randomUUID().toString();
        seed(student, "student", st);
        seed(other, "student", ot);
        for (UUID u : List.of(student, other))
            db.update("INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')", u);
        db.update(
                "INSERT INTO school(id,name,code,region,status,created_by,updated_by)"
                        + " VALUES(?,'交付院校',?,'浙江','ENABLED',?,?)",
                school,
                school.toString(),
                adminId,
                adminId);
        db.update(
                "INSERT INTO major(id,name,code,school_id,level,status,created_by,updated_by)"
                        + " VALUES(?,'交付专业',?,?,'BACHELOR','ENABLED',?,?)",
                major,
                major.toString(),
                school,
                adminId,
                adminId);
        db.update(
                "INSERT INTO"
                    + " software_order(id,order_no,title,student_id,student_phone_digest,student_phone_ciphertext,amount,currency,package_code,source,school_id,major_id,status,created_by,updated_by)"
                    + " VALUES(?,?,'交付订单',?,?,'test-only',100,'CNY','STANDARD','MANUAL',?,?,'CONFIRMED',?,?)",
                order,
                order.toString(),
                student,
                UUID.randomUUID().toString(),
                school,
                major,
                adminId,
                adminId);
        db.update(
                "INSERT INTO"
                    + " software_project(id,order_id,title,requirements,delivery_date,created_by,updated_by)"
                    + " VALUES(?,?,'交付项目','测试',current_date+30,?,?)",
                project,
                order,
                adminId,
                adminId);
        db.update(
                "INSERT INTO"
                    + " storage_config(id,name,provider,bucket,region,endpoint,credential_ref,access,status,created_by,updated_by)"
                    + " VALUES(?,'交付测试','S3_COMPATIBLE','zx-project-private',?,?,'local-minio','PRIVATE','ENABLED',?,?)"
                    + " ON CONFLICT(id) DO NOTHING",
                store,
                System.getenv("ZX_S3_REGION"),
                System.getenv("ZX_S3_ENDPOINT"),
                adminId,
                adminId);
        String adminPath = "/api/v1/admin/projects/" + project + "/deliveries",
                studentPath = "/api/v1/student/projects/" + project + "/deliveries";
        UUID previous = null, previousAsset = null;
        try {
            String
                    convBody =
                            json.writeValueAsString(Map.of("projectId", project, "title", "仅本人私聊")),
                    convKey = UUID.randomUUID().toString();
            String conversation = null;
            for (int n = 0; n < 2; n++) {
                String cid =
                        json.readTree(
                                        mvc.perform(
                                                        post("/api/v1/student/conversations")
                                                                .header(
                                                                        "Authorization",
                                                                        "Bearer " + st)
                                                                .header("Idempotency-Key", convKey)
                                                                .contentType("application/json")
                                                                .content(convBody))
                                                .andExpect(status().isCreated())
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString())
                                .get("id")
                                .asText();
                if (conversation != null) assertEquals(conversation, cid);
                conversation = cid;
            }
            mvc.perform(
                            get("/api/v1/student/conversations/" + conversation)
                                    .header("Authorization", "Bearer " + ot))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            get("/api/v1/student/conversations/" + conversation)
                                    .header("Authorization", "Bearer " + partnerToken))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get("/api/v1/student/conversations")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", "仅本人"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
            UUID journalModel = UUID.randomUUID(),
                    journalPrice = UUID.randomUUID(),
                    journalRun = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " model_definition(id,name,code,provider,endpoint,context_limit,max_output_tokens,status,created_by,updated_by)"
                        + " VALUES(?,'日志测试',?,'TEST','https://model.invalid',4096,1024,'ENABLED',?,?)",
                    journalModel,
                    journalModel.toString(),
                    adminId,
                    adminId);
            db.update(
                    "INSERT INTO"
                        + " model_price(id,model_id,currency,input_per_million,output_per_million,cached_input_per_million,effective_from,status,created_by,updated_by)"
                        + " VALUES(?,?,'CNY',1,1,1,now(),'ENABLED',?,?)",
                    journalPrice,
                    journalModel,
                    adminId,
                    adminId);
            db.update(
                    "INSERT INTO"
                        + " generation_run(id,student_id,conversation_id,client_message_id,request_hash,model_id,price_id)"
                        + " VALUES(?,?,?,?,'test',?,?)",
                    journalRun,
                    student,
                    UUID.fromString(conversation),
                    UUID.randomUUID(),
                    journalModel,
                    journalPrice);
            assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () ->
                            db.update(
                                    "UPDATE generation_run SET student_id=? WHERE id=?",
                                    other,
                                    journalRun));
            UUID userMessage = messages.save(journalRun, "USER", "独立消息问题");
            assertEquals(userMessage, messages.save(journalRun, "USER", "独立消息问题"));
            assertNull(runLeases.claim(UUID.randomUUID())); // 未预占额度的运行不允许被执行器领取。
            UUID journalAccount = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " quota_account(id,user_id,period_start,period_end,granted_tokens,reserved_tokens)"
                        + " VALUES(?,?,date_trunc('month',now()),date_trunc('month',now())+interval"
                        + " '1 month',10,1)",
                    journalAccount,
                    student);
            db.update(
                    "INSERT INTO quota_hold(run_id,account_id,price_id,tokens,amount)"
                            + " VALUES(?,?,?,?,0)",
                    journalRun,
                    journalAccount,
                    journalPrice,
                    1);
            db.update(
                    "INSERT INTO quota_ledger(id,account_id,source_id,kind,grant_tokens)"
                            + " VALUES(?,?,?,'GRANT',10)",
                    UUID.randomUUID(),
                    journalAccount,
                    UUID.randomUUID());
            db.update(
                    "INSERT INTO quota_ledger(id,account_id,source_id,kind,reserve_tokens)"
                            + " VALUES(?,?,?,'RESERVE',1)",
                    UUID.randomUUID(),
                    journalAccount,
                    journalRun);
            UUID worker1 = UUID.randomUUID(), worker2 = UUID.randomUUID();
            var lease1 = runLeases.claim(worker1);
            assertEquals(journalRun, lease1.id());
            assertNull(runLeases.claim(worker2));
            db.update(
                    "UPDATE generation_run SET lease_until=now()-interval '1 second' WHERE id=?",
                    journalRun);
            var lease2 = runLeases.claim(worker2);
            assertTrue(lease2.generation() > lease1.generation());
            assertFalse(runLeases.start(journalRun, worker1, lease1.generation()));
            assertTrue(runLeases.start(journalRun, worker2, lease2.generation()));
            assertFalse(runLeases.start(journalRun, worker2, lease2.generation()));
            db.update(
                    "UPDATE generation_run SET lease_until=now()-interval '1 second' WHERE id=?",
                    journalRun);
            assertNull(runLeases.claim(worker1)); // 已开始调用，即使租约失效也不能重发付费请求。
            assertEquals(1, journal.append(journalRun, "accepted", Map.of("state", "RESERVED")));
            try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var one =
                        workers.submit(
                                () -> journal.append(journalRun, "delta", Map.of("text", "甲")));
                var two =
                        workers.submit(
                                () -> journal.append(journalRun, "delta", Map.of("text", "乙")));
                assertEquals(Set.of(2L, 3L), Set.of(one.get(), two.get()));
            }
            assertEquals(
                    List.of(1L, 2L, 3L),
                    journal.replay(journalRun, 0, 100).stream()
                            .map(cn.zhixiang.platform.chat.entity.RunJournalEventEntity::sequence)
                            .toList());
            assertEquals(2, journal.cursor(journalRun, journalRun + ":2"));
            assertEquals(1, journal.replay(journalRun, 2, 100).size());
            assertThrows(
                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                    () -> journal.cursor(journalRun, UUID.randomUUID() + ":2"));
            assertThrows(
                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                    () -> journal.replay(journalRun, 4, 100));
            assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () ->
                            db.update(
                                    "UPDATE run_event SET payload='{}' WHERE run_id=?",
                                    journalRun));
            var usage =
                    new cn.zhixiang.platform.chat.port.ModelGateway.Usage(
                            2, 1, 0, "isolated-provider-id");
            assertThrows(
                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                    () ->
                            completion.success(
                                    journalRun,
                                    worker2,
                                    lease2.generation(),
                                    "x".repeat(262145),
                                    usage));
            assertEquals(
                    "RESERVED",
                    db.queryForObject(
                            "SELECT state FROM quota_hold WHERE run_id=?",
                            String.class,
                            journalRun));
            assertEquals(
                    0L,
                    db.queryForObject(
                            "SELECT spent_tokens FROM quota_account WHERE id=?",
                            Long.class,
                            journalAccount));
            assertFalse(
                    completion.success(journalRun, worker1, lease1.generation(), "旧执行器结果", usage));
            assertTrue(
                    completion.success(journalRun, worker2, lease2.generation(), "完整回答甲乙", usage));
            assertTrue(
                    completion.success(journalRun, worker2, lease2.generation(), "完整回答甲乙", usage));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM quota_ledger WHERE source_id=? AND kind='SETTLE'",
                            Integer.class,
                            journalRun));
            assertEquals(
                    5L,
                    db.queryForObject(
                            "SELECT last_sequence FROM generation_run WHERE id=?",
                            Long.class,
                            journalRun));
            UUID answer =
                    db.queryForObject(
                            "SELECT id FROM chat_message WHERE run_id=? AND role='ASSISTANT'",
                            UUID.class,
                            journalRun);
            assertEquals(answer, messages.save(journalRun, "ASSISTANT", "完整回答甲乙"));
            assertThrows(
                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                    () -> messages.save(journalRun, "ASSISTANT", "覆盖原回答"));
            assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () -> db.update("UPDATE chat_message SET text='覆盖' WHERE id=?", answer));
            mvc.perform(
                            get("/api/v1/student/conversations/" + conversation + "/messages")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", "完整回答"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].text").value("完整回答甲乙"))
                    .andExpect(jsonPath("$.items[0].citations").isEmpty());
            mvc.perform(
                            get("/api/v1/student/conversations/" + conversation + "/messages")
                                    .header("Authorization", "Bearer " + ot))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            get("/api/v1/student/conversations/" + conversation + "/messages")
                                    .header("Authorization", "Bearer " + partnerToken))
                    .andExpect(status().isForbidden());
            assertThrows(
                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                    () -> journal.append(journalRun, "delta", Map.of("text", "终态事件后拒绝")));
            UUID sessionId =
                    db.queryForObject(
                            "SELECT id FROM auth_session WHERE user_id=?", UUID.class, student);
            long authVersion =
                    db.queryForObject(
                            "SELECT auth_version FROM user_account WHERE id=?",
                            Long.class,
                            student);
            var streamActor =
                    new cn.zhixiang.platform.identity.model.Actor(
                            student, "student", authVersion, sessionId);
            assertTrue(streamAccess.allowed(journalRun, streamActor));
            db.update("UPDATE user_account SET auth_version=auth_version+1 WHERE id=?", student);
            assertFalse(streamAccess.allowed(journalRun, streamActor));
            db.update("UPDATE user_account SET auth_version=? WHERE id=?", authVersion, student);
            mvc.perform(
                            get("/api/v1/student/runs/" + journalRun + "/events")
                                    .header("Authorization", "Bearer " + ot))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            get("/api/v1/student/runs/" + journalRun + "/events")
                                    .header("Authorization", "Bearer " + partnerToken))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get("/api/v1/student/runs/" + journalRun + "/events")
                                    .header("Authorization", "Bearer " + st)
                                    .header("Last-Event-ID", UUID.randomUUID() + ":2"))
                    .andExpect(status().isBadRequest());
            var stream =
                    mvc.perform(
                                    get("/api/v1/student/runs/" + journalRun + "/events")
                                            .header("Authorization", "Bearer " + st)
                                            .header("Last-Event-ID", journalRun + ":2"))
                            .andExpect(request().asyncStarted())
                            .andReturn();
            stream.getAsyncResult(5000);
            String replay =
                    mvc.perform(asyncDispatch(stream))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertTrue(replay.contains("id:" + journalRun + ":3"));
            assertTrue(replay.contains("event:completed"));
            assertFalse(replay.contains("id:" + journalRun + ":2"));
            var http =
                    java.net.http.HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofSeconds(3))
                            .build();
            var request =
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create(
                                            "http://127.0.0.1:"
                                                    + httpPort
                                                    + "/api/v1/student/runs/"
                                                    + journalRun
                                                    + "/events"))
                            .header("Authorization", "Bearer " + st)
                            .header("Last-Event-ID", journalRun + ":2")
                            .timeout(java.time.Duration.ofSeconds(5))
                            .GET()
                            .build();
            var networkReplay =
                    http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, networkReplay.statusCode());
            assertTrue(networkReplay.body().contains("event:completed"));
            assertFalse(networkReplay.body().contains("id:" + journalRun + ":2"));
            var exhausted =
                    java.net.http.HttpRequest.newBuilder(request.uri())
                            .header("Authorization", "Bearer " + st)
                            .header("Last-Event-ID", journalRun + ":5")
                            .timeout(java.time.Duration.ofSeconds(5))
                            .GET()
                            .build();
            assertEquals(
                    "",
                    http.send(exhausted, java.net.http.HttpResponse.BodyHandlers.ofString())
                            .body());

            assertThrows(
                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                    () -> journal.append(journalRun, "delta", Map.of("text", "结束后拒绝")));
            db.update("DELETE FROM run_event WHERE run_id=? AND sequence=1", journalRun);
            assertEquals(
                    "STREAM_REPLAY_EXPIRED",
                    assertThrows(
                                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                                    () -> journal.replay(journalRun, 0, 100))
                            .code());
            assertEquals(4, journal.replay(journalRun, 1, 100).size());
            for (boolean noCharge : List.of(false, true)) {
                UUID failedRun = UUID.randomUUID();
                db.update(
                        "INSERT INTO"
                            + " generation_run(id,student_id,conversation_id,client_message_id,request_hash,model_id,price_id,state,call_started_at,lease_owner,lease_until,generation)"
                            + " SELECT"
                            + " ?,student_id,conversation_id,?,'failure-test',model_id,price_id,'RUNNING',now(),?,now()+interval"
                            + " '1 minute',1 FROM generation_run WHERE id=?",
                        failedRun,
                        UUID.randomUUID(),
                        worker2,
                        journalRun);
                db.update(
                        "INSERT INTO quota_hold(run_id,account_id,price_id,tokens,amount)"
                                + " VALUES(?,?,?,1,0)",
                        failedRun,
                        journalAccount,
                        journalPrice);
                db.update(
                        "UPDATE quota_account SET reserved_tokens=reserved_tokens+1 WHERE id=?",
                        journalAccount);
                db.update(
                        "INSERT INTO quota_ledger(id,account_id,source_id,kind,reserve_tokens)"
                                + " VALUES(?,?,?,'RESERVE',1)",
                        UUID.randomUUID(),
                        journalAccount,
                        failedRun);
                var failed =
                        new cn.zhixiang.platform.chat.port.ModelGateway.Failure(
                                noCharge ? "MODEL_HTTP_429" : "MODEL_USAGE_UNKNOWN", noCharge);
                assertTrue(completion.failure(failedRun, worker2, 1, failed));
                assertFalse(completion.failure(failedRun, worker2, 1, failed));
                assertEquals(
                        noCharge ? "RELEASED" : "RECONCILING",
                        db.queryForObject(
                                "SELECT state FROM quota_hold WHERE run_id=?",
                                String.class,
                                failedRun));
                assertEquals(
                        "FAILED",
                        db.queryForObject(
                                "SELECT state FROM generation_run WHERE id=?",
                                String.class,
                                failedRun));
                assertEquals(1, journal.replay(failedRun, 0, 100).size());
            }
            UUID flowModel = UUID.randomUUID(),
                    flowPrice = UUID.randomUUID(),
                    flowKey = UUID.randomUUID(),
                    flowPlan = UUID.randomUUID(),
                    flowPlanVersion = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " model_definition(id,name,code,provider,endpoint,context_limit,max_output_tokens,status,created_by,updated_by)"
                        + " VALUES(?,'真实运行协议测试',?,'OPENAI_COMPATIBLE','https://model.invalid/chat/completions',65536,1024,'ENABLED',?,?)",
                    flowModel,
                    flowModel.toString(),
                    adminId,
                    adminId);
            db.update(
                    "INSERT INTO"
                        + " model_price(id,model_id,currency,input_per_million,output_per_million,cached_input_per_million,effective_from,status,created_by,updated_by)"
                        + " VALUES(?,?,'CNY',1,1,1,now()-interval '1 day','ENABLED',?,?)",
                    flowPrice,
                    flowModel,
                    adminId,
                    adminId);
            mvc.perform(
                            get("/api/v1/student/models")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", "运行模型"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            UUID secretRef = modelSecrets.put("isolated-run-key");
            db.update(
                    "INSERT INTO"
                        + " model_key(id,model_id,name,secret_ref,masked_suffix,status,created_by,updated_by)"
                        + " VALUES(?,?,'隔离调用',?,'****','ENABLED',?,?)",
                    flowKey,
                    flowModel,
                    secretRef,
                    adminId,
                    adminId);
            db.update(
                    "INSERT INTO membership_plan(id,name,code,status,created_by,updated_by)"
                            + " VALUES(?,'运行测试会员',?,'ENABLED',?,?)",
                    flowPlan,
                    flowPlan.toString(),
                    adminId,
                    adminId);
            db.update(
                    "INSERT INTO"
                        + " membership_plan_version(id,plan_id,version_number,duration_days,monthly_token_quota,monthly_amount_limit,effective_from)"
                        + " VALUES(?,?,1,30,100000,10,now())",
                    flowPlanVersion,
                    flowPlan);
            db.update(
                    "INSERT INTO"
                        + " membership(id,user_id,plan_id,plan_version_id,starts_at,expires_at,created_by)"
                        + " VALUES(?,?,?,?,now()-interval '1 minute',now()+interval '1 day',?)",
                    UUID.randomUUID(),
                    student,
                    flowPlan,
                    flowPlanVersion,
                    adminId);
            mvc.perform(
                            get("/api/v1/student/models")
                                    .header("Authorization", "Bearer " + st)
                                    .param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(flowModel.toString()))
                    .andExpect(jsonPath("$.items[0].endpoint").doesNotExist());
            mvc.perform(
                            get("/api/v1/student/models")
                                    .header("Authorization", "Bearer " + st)
                                    .param("page", "2")
                                    .param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.total").value(1));
            mvc.perform(
                            get("/api/v1/student/models")
                                    .header("Authorization", "Bearer " + st)
                                    .param("status", "DISABLED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            mvc.perform(
                            get("/api/v1/student/models")
                                    .header("Authorization", "Bearer " + st)
                                    .param("sort", "name;DROP TABLE"))
                    .andExpect(status().isBadRequest());
            org.mockito.Mockito.doAnswer(
                    invocation -> {
                        assertFalse(
                                org.springframework.transaction.support
                                        .TransactionSynchronizationManager
                                        .isActualTransactionActive());
                        cn.zhixiang.platform.chat.port.ModelGateway.Request submitted =
                                invocation.getArgument(0);
                        assertEquals("isolated-run-key", submitted.secret());
                        java.util.function.Consumer<String> delta = invocation.getArgument(1);
                        delta.accept("端到端");
                        delta.accept("回答");
                        return new cn.zhixiang.platform.chat.port.ModelGateway.Usage(
                                10, 5, 0, "isolated-flow");
                    })
                    .when(modelGateway)
                    .stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            var runBody =
                    new HashMap<String, Object>(
                            Map.of(
                                    "conversationId",
                                    conversation,
                                    "clientMessageId",
                                    UUID.randomUUID(),
                                    "text",
                                    "请解释项目结构",
                                    "modelId",
                                    flowModel,
                                    "sourceVersionIds",
                                    List.of(),
                                    "maxOutputTokens",
                                    100));
            String flowRun = null;
            for (int repeat = 0; repeat < 2; repeat++) {
                String created =
                        json.readTree(
                                        mvc.perform(
                                                        post("/api/v1/student/runs")
                                                                .header(
                                                                        "Authorization",
                                                                        "Bearer " + st)
                                                                .header(
                                                                        "Idempotency-Key",
                                                                        UUID.randomUUID())
                                                                .contentType("application/json")
                                                                .content(
                                                                        json.writeValueAsString(
                                                                                runBody)))
                                                .andExpect(status().isAccepted())
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString())
                                .get("id")
                                .asText();
                if (flowRun != null) assertEquals(flowRun, created);
                flowRun = created;
            }
            runBody.put("text", "相同客户端ID不允许替换问题");
            mvc.perform(
                            post("/api/v1/student/runs")
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(runBody)))
                    .andExpect(status().isConflict());
            assertTrue(runExecutor.runOne());
            assertFalse(runExecutor.runOne());
            mvc.perform(
                            get("/api/v1/student/runs/" + flowRun)
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.billingState").value("SETTLED"));
            mvc.perform(
                            get("/api/v1/student/runs/" + flowRun)
                                    .header("Authorization", "Bearer " + ot))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            get("/api/v1/student/conversations/" + conversation + "/messages")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", "端到端回答"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM quota_ledger WHERE source_id=? AND kind='SETTLE'",
                            Integer.class,
                            UUID.fromString(flowRun)));
            org.mockito.Mockito.verify(modelGateway, org.mockito.Mockito.times(1)).stream(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            for (String action : List.of("queued", "running", "expired")) {
                runBody.put("clientMessageId", UUID.randomUUID());
                UUID controlled =
                        UUID.fromString(
                                json.readTree(
                                                mvc.perform(
                                                                post("/api/v1/student/runs")
                                                                        .header(
                                                                                "Authorization",
                                                                                "Bearer " + st)
                                                                        .header(
                                                                                "Idempotency-Key",
                                                                                UUID.randomUUID())
                                                                        .contentType(
                                                                                "application/json")
                                                                        .content(
                                                                                json
                                                                                        .writeValueAsString(
                                                                                                runBody)))
                                                        .andExpect(status().isAccepted())
                                                        .andReturn()
                                                        .getResponse()
                                                        .getContentAsString())
                                        .get("id")
                                        .asText());
                if (!action.equals("queued")) {
                    UUID owner = UUID.randomUUID();
                    var claim = runLeases.claim(owner);
                    assertEquals(controlled, claim.id());
                    assertTrue(runLeases.start(controlled, owner, claim.generation()));
                }
                if (action.equals("expired")) {
                    db.update(
                            "UPDATE generation_run SET lease_until=now()-interval '1 second' WHERE"
                                    + " id=?",
                            controlled);
                    assertTrue(runControl.expireOne());
                    assertFalse(runControl.expireOne());
                } else
                    mvc.perform(
                                    post("/api/v1/student/runs/" + controlled + "/cancel")
                                            .header("Authorization", "Bearer " + st)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .contentType("application/json")
                                            .content("{\"reason\":\"隔离取消测试\"}"))
                            .andExpect(status().isAccepted())
                            .andExpect(jsonPath("$.state").value("CANCELLED"));
                assertEquals(
                        action.equals("queued") ? "RELEASED" : "RECONCILING",
                        db.queryForObject(
                                "SELECT state FROM quota_hold WHERE run_id=?",
                                String.class,
                                controlled));
                assertFalse(runExecutor.runOne());
            }
            org.mockito.Mockito.verify(modelGateway, org.mockito.Mockito.times(1)).stream(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            // 排队后禁用凭证必须在网络调用前拒绝，释放预占且不得消耗供应商用量。
            runBody.put("clientMessageId", UUID.randomUUID());
            UUID disabledKeyRun =
                    UUID.fromString(
                            json.readTree(
                                            mvc.perform(
                                                            post("/api/v1/student/runs")
                                                                    .header(
                                                                            "Authorization",
                                                                            "Bearer " + st)
                                                                    .header(
                                                                            "Idempotency-Key",
                                                                            UUID.randomUUID())
                                                                    .contentType("application/json")
                                                                    .content(
                                                                            json.writeValueAsString(
                                                                                    runBody)))
                                                    .andExpect(status().isAccepted())
                                                    .andReturn()
                                                    .getResponse()
                                                    .getContentAsString())
                                    .get("id")
                                    .asText());
            db.update("UPDATE model_key SET status='DISABLED' WHERE id=?", flowKey);
            mvc.perform(get("/api/v1/student/models").header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            assertTrue(runExecutor.runOne());
            assertFalse(runExecutor.runOne());
            mvc.perform(
                            get("/api/v1/student/runs/" + disabledKeyRun)
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("FAILED"))
                    .andExpect(jsonPath("$.billingState").value("RELEASED"))
                    .andExpect(jsonPath("$.errorCode").value("MODEL_CONFIGURATION_INVALID"));
            org.mockito.Mockito.verify(modelGateway, org.mockito.Mockito.times(1)).stream(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM quota_ledger WHERE source_id=? AND kind='SETTLE'",
                            Integer.class,
                            disabledKeyRun));
            db.update("UPDATE model_key SET status='ENABLED' WHERE id=?", flowKey);
            mvc.perform(
                            get("/api/v1/student/usage")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", flowRun)
                                    .param("status", "SETTLED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].inputTokens").value(10))
                    .andExpect(jsonPath("$.items[0].outputTokens").value(5))
                    .andExpect(jsonPath("$.items[0].amount").value("0.000015"));
            mvc.perform(
                            get("/api/v1/student/usage")
                                    .header("Authorization", "Bearer " + ot)
                                    .param("q", flowRun))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            mvc.perform(
                            get("/api/v1/student/usage")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", disabledKeyRun.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].state").value("RELEASED"))
                    .andExpect(jsonPath("$.items[0].amount").value("0"));
            mvc.perform(
                            get("/api/v1/student/usage")
                                    .header("Authorization", "Bearer " + st)
                                    .param("status", "RECONCILING")
                                    .param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(3))
                    .andExpect(jsonPath("$.items[0].inputTokens").isEmpty())
                    .andExpect(jsonPath("$.items[0].amount").isEmpty());
            mvc.perform(
                            get("/api/v1/student/usage")
                                    .header("Authorization", "Bearer " + st)
                                    .param("from", "2099-01-01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            mvc.perform(
                            get("/api/v1/student/usage")
                                    .header("Authorization", "Bearer " + st)
                                    .param("from", "2026-12-31")
                                    .param("to", "2026-01-01"))
                    .andExpect(status().isBadRequest());
            mvc.perform(
                            get("/api/v1/student/usage")
                                    .header("Authorization", "Bearer " + st)
                                    .param("status", "ENABLED"))
                    .andExpect(status().isBadRequest());
            mvc.perform(
                            get("/api/v1/admin/usage")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("studentId", student.toString())
                                    .param("q", flowRun))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].text").doesNotExist());
            mvc.perform(
                            get("/api/v1/admin/usage")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("studentId", UUID.randomUUID().toString())
                                    .param("q", flowRun))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            mvc.perform(get("/api/v1/admin/usage").header("Authorization", "Bearer " + st))
                    .andExpect(status().isForbidden());
            var adjustment =
                    Map.of(
                            "runId",
                            flowRun,
                            "direction",
                            "CREDIT",
                            "amount",
                            "0.000010",
                            "reason",
                            "隔离退还测试",
                            "evidenceRef",
                            "test-evidence-001");
            UUID adjustmentKey = UUID.randomUUID();
            String adjustmentId = null;
            for (int n = 0; n < 2; n++) {
                String actual =
                        json.readTree(
                                        mvc.perform(
                                                        post("/api/v1/admin/usage-adjustments")
                                                                .header(
                                                                        "Authorization",
                                                                        "Bearer " + adminToken)
                                                                .header(
                                                                        "Idempotency-Key",
                                                                        adjustmentKey)
                                                                .contentType("application/json")
                                                                .content(
                                                                        json.writeValueAsString(
                                                                                adjustment)))
                                                .andExpect(status().isCreated())
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString())
                                .get("id")
                                .asText();
                if (adjustmentId != null) assertEquals(adjustmentId, actual);
                adjustmentId = actual;
            }
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM quota_ledger WHERE source_id=? AND"
                                    + " kind='ADJUSTMENT'",
                            Integer.class,
                            UUID.fromString(adjustmentId)));
            String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
            var stats =
                    json.readTree(
                            mvc.perform(
                                            get("/api/v1/student/statistics")
                                                    .header("Authorization", "Bearer " + st)
                                                    .param("from", today)
                                                    .param("to", today))
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            var metrics = new HashMap<String, String>();
            stats.get("items")
                    .forEach(x -> metrics.put(x.get("metric").asText(), x.get("value").asText()));
            assertEquals("0.000018", metrics.get("model_settled_amount"));
            assertEquals("18", metrics.get("model_settled_tokens"));
            assertEquals("-0.000010", metrics.get("model_adjustment_amount"));
            assertEquals("3", metrics.get("model_unresolved_runs"));
            mvc.perform(
                            get("/api/v1/student/statistics")
                                    .header("Authorization", "Bearer " + ot)
                                    .param("from", today)
                                    .param("to", today))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty());

            mvc.perform(
                            get("/api/v1/student/usage")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", flowRun))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].amount").value("0.000015"))
                    .andExpect(jsonPath("$.items[0].adjustmentAmount").value("-0.000010"))
                    .andExpect(jsonPath("$.items[0].netAmount").value("0.000005"));

            assertEquals(
                    0,
                    new java.math.BigDecimal("0.000015")
                            .compareTo(
                                    db.queryForObject(
                                            "SELECT actual_amount FROM quota_hold WHERE run_id=?",
                                            java.math.BigDecimal.class,
                                            UUID.fromString(flowRun))));
            mvc.perform(
                            post("/api/v1/admin/usage-adjustments")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(adjustment)))
                    .andExpect(status().isUnprocessableEntity());
            var unknownAdjustment = new HashMap<String, Object>(adjustment);
            unknownAdjustment.put("runId", disabledKeyRun.toString());
            mvc.perform(
                            post("/api/v1/admin/usage-adjustments")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(unknownAdjustment)))
                    .andExpect(status().isConflict());
            mvc.perform(
                            post("/api/v1/admin/usage-adjustments")
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(adjustment)))
                    .andExpect(status().isForbidden());
            var credit =
                    new cn.zhixiang.platform.billing.dto.AdjustmentDtos.Write(
                            UUID.fromString(flowRun),
                            cn.zhixiang.platform.billing.dto.AdjustmentDtos.Direction.CREDIT,
                            "0.000005",
                            "并发退还测试",
                            "test-concurrent-evidence");
            var adminActor =
                    new cn.zhixiang.platform.identity.model.Actor(
                            adminId, "admin", 1, UUID.randomUUID());
            var barrier = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                java.util.concurrent.Callable<Boolean> refund =
                        () -> {
                            barrier.await();
                            try {
                                adjustments.create(credit, adminActor);
                                return true;
                            } catch (
                                    cn.zhixiang.platform.shared.exception.BusinessException
                                            rejected) {
                                return false;
                            }
                        };
                var a = pool.submit(refund);
                var b = pool.submit(refund);
                barrier.countDown();
                assertNotEquals(
                        a.get(10, java.util.concurrent.TimeUnit.SECONDS),
                        b.get(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            mvc.perform(
                            get("/api/v1/student/usage")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", flowRun))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].netAmount").value("0.000000"));
            // 本人资料只修改白名单字段，不通过昵称变更吊销会话或更换角色。
            String profileEtag =
                    mvc.perform(get("/api/v1/student/me").header("Authorization", "Bearer " + st))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.id").value(student.toString()))
                            .andReturn()
                            .getResponse()
                            .getHeader("ETag");
            var profileBody = Map.of("displayName", "已更新昵称", "schoolId", school, "majorId", major);
            UUID profileKey = UUID.randomUUID();
            for (int n = 0; n < 2; n++)
                mvc.perform(
                                put("/api/v1/student/me")
                                        .header("Authorization", "Bearer " + st)
                                        .header("Idempotency-Key", profileKey)
                                        .header("If-Match", profileEtag)
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(profileBody)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.displayName").value("已更新昵称"))
                        .andExpect(jsonPath("$.platform").value("student"));
            mvc.perform(
                            put("/api/v1/student/me")
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", profileEtag)
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(profileBody)))
                    .andExpect(status().isPreconditionFailed());
            mvc.perform(get("/api/v1/partner/me").header("Authorization", "Bearer " + st))
                    .andExpect(status().isForbidden());
            UUID profilePartner = UUID.randomUUID();
            String profilePartnerToken = UUID.randomUUID().toString();
            seed(profilePartner, "partner", profilePartnerToken);
            db.update(
                    "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000003')",
                    profilePartner);
            String partnerEtag =
                    mvc.perform(
                                    get("/api/v1/partner/me")
                                            .header(
                                                    "Authorization",
                                                    "Bearer " + profilePartnerToken))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getHeader("ETag");
            mvc.perform(
                            put("/api/v1/partner/me")
                                    .header("Authorization", "Bearer " + profilePartnerToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", partnerEtag)
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(profileBody)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.platform").value("partner"));
            for (String platform : List.of("student", "partner")) {
                String token = platform.equals("student") ? st : profilePartnerToken;
                mvc.perform(
                                get("/api/v1/" + platform + "/dictionaries/schools")
                                        .header("Authorization", "Bearer " + token)
                                        .param("q", school.toString()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.total").value(1));
                mvc.perform(
                                get("/api/v1/" + platform + "/dictionaries/majors")
                                        .header("Authorization", "Bearer " + token)
                                        .param("schoolId", school.toString()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.total").value(1));
                mvc.perform(
                                get("/api/v1/" + platform + "/dictionaries/majors")
                                        .header("Authorization", "Bearer " + token)
                                        .param("schoolId", UUID.randomUUID().toString()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.total").value(0));
                mvc.perform(
                                get("/api/v1/" + platform + "/dictionaries/schools")
                                        .header("Authorization", "Bearer " + token)
                                        .param("status", "DISABLED"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.total").value(0));
            }
            db.update("UPDATE school SET status='DISABLED' WHERE id=?", school);
            mvc.perform(
                            get("/api/v1/student/dictionaries/majors")
                                    .header("Authorization", "Bearer " + st)
                                    .param("schoolId", school.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            db.update("UPDATE school SET status='ENABLED' WHERE id=?", school);
            mvc.perform(
                            get("/api/v1/partner/students/" + student)
                                    .header("Authorization", "Bearer " + profilePartnerToken))
                    .andExpect(status().isNotFound());
            db.update("UPDATE software_order SET partner_id=? WHERE id=?", profilePartner, order);
            mvc.perform(
                            get("/api/v1/partner/students")
                                    .header("Authorization", "Bearer " + profilePartnerToken)
                                    .param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(student.toString()))
                    .andExpect(jsonPath("$.items[0].roleIds").doesNotExist());
            mvc.perform(
                            get("/api/v1/partner/students/" + student)
                                    .header("Authorization", "Bearer " + profilePartnerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.activeOrderCount").value(1))
                    .andExpect(jsonPath("$.schoolName").value("交付院校"));
            mvc.perform(
                            get("/api/v1/partner/students/" + other)
                                    .header("Authorization", "Bearer " + profilePartnerToken))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/partner/students").header("Authorization", "Bearer " + st))
                    .andExpect(status().isForbidden());
            db.update("UPDATE software_order SET partner_id=NULL WHERE id=?", order);
            mvc.perform(
                            get("/api/v1/partner/students/" + student)
                                    .header("Authorization", "Bearer " + profilePartnerToken))
                    .andExpect(status().isNotFound());
            // 取消之后迟到用量仅保存证据，通过独立核对结算，不能复活回答或重发请求。
            runBody.put("clientMessageId", UUID.randomUUID());
            UUID lateRun =
                    UUID.fromString(
                            json.readTree(
                                            mvc.perform(
                                                            post("/api/v1/student/runs")
                                                                    .header(
                                                                            "Authorization",
                                                                            "Bearer " + st)
                                                                    .header(
                                                                            "Idempotency-Key",
                                                                            UUID.randomUUID())
                                                                    .contentType("application/json")
                                                                    .content(
                                                                            json.writeValueAsString(
                                                                                    runBody)))
                                                    .andExpect(status().isAccepted())
                                                    .andReturn()
                                                    .getResponse()
                                                    .getContentAsString())
                                    .get("id")
                                    .asText());
            UUID lateOwner = UUID.randomUUID();
            var lateLease = runLeases.claim(lateOwner);
            assertEquals(lateRun, lateLease.id());
            assertFalse(
                    completion.success(
                            lateRun,
                            lateOwner,
                            lateLease.generation(),
                            "未发起调用不得造用量",
                            new cn.zhixiang.platform.chat.port.ModelGateway.Usage(
                                    1, 1, 0, "before-start")));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM run_usage_evidence WHERE run_id=?",
                            Integer.class,
                            lateRun));
            assertTrue(runLeases.start(lateRun, lateOwner, lateLease.generation()));
            mvc.perform(
                            post("/api/v1/student/runs/" + lateRun + "/cancel")
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content("{\"reason\":\"取消等待\"}"))
                    .andExpect(status().isAccepted());
            String failedJob =
                    json.readTree(
                                    mvc.perform(
                                                    post("/api/v1/admin/runs/"
                                                                    + lateRun
                                                                    + "/reconcile")
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + adminToken)
                                                            .header(
                                                                    "Idempotency-Key",
                                                                    UUID.randomUUID())
                                                            .contentType("application/json")
                                                            .content("{\"reason\":\"核对未知费用\"}"))
                                            .andExpect(status().isAccepted())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                            .get("id")
                            .asText();
            assertTrue(reconciliations.processOne());
            assertFalse(reconciliations.processOne());
            mvc.perform(
                            get("/api/v1/admin/jobs/" + failedJob)
                                    .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("FAILED"))
                    .andExpect(
                            jsonPath("$.errorCode").value("PROVIDER_RECONCILIATION_UNAVAILABLE"));
            assertEquals(
                    "RECONCILING",
                    db.queryForObject(
                            "SELECT state FROM quota_hold WHERE run_id=?", String.class, lateRun));
            var lateUsage =
                    new cn.zhixiang.platform.chat.port.ModelGateway.Usage(
                            12, 6, 0, "late-provider-001");
            assertFalse(
                    completion.success(
                            lateRun,
                            UUID.randomUUID(),
                            lateLease.generation(),
                            "拒绝伪造执行器",
                            lateUsage));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM run_usage_evidence WHERE run_id=?",
                            Integer.class,
                            lateRun));
            assertFalse(
                    completion.success(
                            lateRun, lateOwner, lateLease.generation(), "迟到回答不发布", lateUsage));
            assertFalse(
                    completion.success(
                            lateRun, lateOwner, lateLease.generation(), "迟到回答不发布", lateUsage));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM run_usage_evidence WHERE run_id=?",
                            Integer.class,
                            lateRun));
            assertThrows(
                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                    () ->
                            completion.success(
                                    lateRun,
                                    lateOwner,
                                    lateLease.generation(),
                                    "冲突证据",
                                    new cn.zhixiang.platform.chat.port.ModelGateway.Usage(
                                            99, 6, 0, "late-provider-001")));
            UUID reconcileKey = UUID.randomUUID();
            String successfulJob = null;
            for (int n = 0; n < 2; n++) {
                String id =
                        json.readTree(
                                        mvc.perform(
                                                        post("/api/v1/admin/runs/"
                                                                        + lateRun
                                                                        + "/reconcile")
                                                                .header(
                                                                        "Authorization",
                                                                        "Bearer " + adminToken)
                                                                .header(
                                                                        "Idempotency-Key",
                                                                        reconcileKey)
                                                                .contentType("application/json")
                                                                .content(
                                                                        "{\"reason\":\"原调用证据已返回\"}"))
                                                .andExpect(status().isAccepted())
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString())
                                .get("id")
                                .asText();
                if (successfulJob != null) assertEquals(successfulJob, id);
                successfulJob = id;
            }
            // 两个worker竞争同一个核对任务，只允许一个完成账务处理。
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var gate = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<Boolean> reconcile =
                        () -> {
                            gate.await();
                            return reconciliations.processOne();
                        };
                var a = pool.submit(reconcile);
                var b = pool.submit(reconcile);
                gate.countDown();
                assertNotEquals(
                        a.get(10, java.util.concurrent.TimeUnit.SECONDS),
                        b.get(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertFalse(reconciliations.processOne());
            mvc.perform(
                            get("/api/v1/admin/jobs/" + successfulJob)
                                    .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("SUCCEEDED"));
            mvc.perform(
                            get("/api/v1/student/runs/" + lateRun)
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("CANCELLED"))
                    .andExpect(jsonPath("$.billingState").value("SETTLED"));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM quota_ledger WHERE source_id=? AND kind='SETTLE'",
                            Integer.class,
                            lateRun));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM chat_message WHERE run_id=? AND role='ASSISTANT'",
                            Integer.class,
                            lateRun));
            mvc.perform(
                            post("/api/v1/admin/runs/" + lateRun + "/reconcile")
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content("{\"reason\":\"越权核对\"}"))
                    .andExpect(status().isForbidden());
            UUID noChargeRun =
                    db.queryForObject(
                            "SELECT r.id FROM generation_run r JOIN quota_hold h ON h.run_id=r.id"
                                    + " WHERE r.conversation_id=? AND r.state='CANCELLED' AND"
                                    + " h.state='RECONCILING' ORDER BY r.created_at LIMIT 1",
                            UUID.class,
                            UUID.fromString(conversation));
            UUID noChargeOwner =
                    db.queryForObject(
                            "SELECT lease_owner FROM generation_run WHERE id=?",
                            UUID.class,
                            noChargeRun);
            long noChargeGeneration =
                    db.queryForObject(
                            "SELECT generation FROM generation_run WHERE id=?",
                            Long.class,
                            noChargeRun);
            assertFalse(
                    completion.failure(
                            noChargeRun,
                            noChargeOwner,
                            noChargeGeneration,
                            new cn.zhixiang.platform.chat.port.ModelGateway.Failure(
                                    "MODEL_HTTP_429", true)));
            mvc.perform(
                            post("/api/v1/admin/runs/" + noChargeRun + "/reconcile")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content("{\"reason\":\"供应商明确无费用返回\"}"))
                    .andExpect(status().isAccepted());
            assertTrue(reconciliations.processOne());
            assertFalse(reconciliations.processOne());
            assertEquals(
                    "RELEASED",
                    db.queryForObject(
                            "SELECT state FROM quota_hold WHERE run_id=?",
                            String.class,
                            noChargeRun));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM quota_ledger WHERE source_id=? AND"
                                    + " kind='RELEASE'",
                            Integer.class,
                            noChargeRun));
            UUID ragCategory = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " knowledge_category(id,name,code,sort_order,status,created_by,updated_by)"
                        + " VALUES(?,'运行引用',?,0,'ENABLED',?,?)",
                    ragCategory,
                    ragCategory.toString(),
                    adminId,
                    adminId);
            var ragBody =
                    Map.of(
                            "title",
                            "运行引用材料",
                            "categoryId",
                            ragCategory,
                            "tagIds",
                            List.of(),
                            "type",
                            "MARKDOWN",
                            "markdown",
                            "数据库事务保护账务一致性。这是测试资料，不是系统指令。",
                            "audience",
                            "PUBLIC");
            UUID rag =
                    UUID.fromString(
                            json.readTree(
                                            mvc.perform(
                                                            post("/api/v1/admin/knowledge")
                                                                    .header(
                                                                            "Authorization",
                                                                            "Bearer " + adminToken)
                                                                    .header(
                                                                            "Idempotency-Key",
                                                                            UUID.randomUUID())
                                                                    .contentType("application/json")
                                                                    .content(
                                                                            json.writeValueAsString(
                                                                                    ragBody)))
                                                    .andExpect(status().isCreated())
                                                    .andReturn()
                                                    .getResponse()
                                                    .getContentAsString())
                                    .get("id")
                                    .asText());
            mvc.perform(
                            post("/api/v1/admin/knowledge/" + rag + "/submit-review")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content("{\"reason\":\"测试审核\"}"))
                    .andExpect(status().isOk());
            String published =
                    mvc.perform(
                                    post("/api/v1/admin/knowledge/" + rag + "/publish")
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .header("If-Match", "\"2\"")
                                            .contentType("application/json")
                                            .content("{\"reason\":\"测试发布\"}"))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            UUID ragVersion =
                    UUID.fromString(json.readTree(published).get("publishedVersionId").asText());
            assertTrue(knowledgeIndexer.runOne());
            cn.zhixiang.platform.chat.adapter.LocalModelServer localModel =
                    new cn.zhixiang.platform.chat.adapter.LocalModelServer();
            db.update(
                    "UPDATE model_definition SET endpoint=? WHERE id=?",
                    localModel.endpoint(),
                    flowModel);
            org.mockito.Mockito.doAnswer(
                    invocation -> {
                        cn.zhixiang.platform.chat.port.ModelGateway.Request context =
                                invocation.getArgument(0);
                        assertTrue(
                                context.messages().stream()
                                        .anyMatch(t -> t.content().contains("数据库事务保护账务一致性")));
                        assertFalse(
                                org.springframework.transaction.support
                                        .TransactionSynchronizationManager
                                        .isActualTransactionActive());
                        return localModel.gateway().stream(context, invocation.getArgument(1));
                    })
                    .when(modelGateway)
                    .stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            runBody.put("clientMessageId", UUID.randomUUID());
            runBody.put("sourceVersionIds", List.of(ragVersion));
            UUID ragRun;
            try {
                ragRun =
                        UUID.fromString(
                                json.readTree(
                                                mvc.perform(
                                                                post("/api/v1/student/runs")
                                                                        .header(
                                                                                "Authorization",
                                                                                "Bearer " + st)
                                                                        .header(
                                                                                "Idempotency-Key",
                                                                                UUID.randomUUID())
                                                                        .contentType(
                                                                                "application/json")
                                                                        .content(
                                                                                json
                                                                                        .writeValueAsString(
                                                                                                runBody)))
                                                        .andExpect(status().isAccepted())
                                                        .andReturn()
                                                        .getResponse()
                                                        .getContentAsString())
                                        .get("id")
                                        .asText());
                assertTrue(runExecutor.runOne());
                assertEquals(1, localModel.calls());
            } finally {
                localModel.close();
            }
            mvc.perform(
                            get("/api/v1/student/conversations/" + conversation + "/messages")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", "引用答复"))
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath("$.items[0].citations[0].versionId")
                                    .value(ragVersion.toString()));
            mvc.perform(
                            post("/api/v1/admin/knowledge/" + rag + "/withdraw")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"3\"")
                                    .contentType("application/json")
                                    .content("{\"reason\":\"撤回验证权限\"}"))
                    .andExpect(status().isOk());
            mvc.perform(
                            get("/api/v1/student/runs/" + ragRun)
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            get("/api/v1/student/runs/" + ragRun + "/events")
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            get("/api/v1/student/conversations/" + conversation + "/messages")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", "引用答复"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            mvc.perform(
                            get("/api/v1/admin/conversations")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("reason", "隔离审计目录")
                                    .param("q", "交付答疑"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(
                            get("/api/v1/admin/conversations/" + conversation + "/messages")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("reason", "隔离审计回溯")
                                    .param("q", "引用答复"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(
                            jsonPath("$.items[0].citations[0].versionId")
                                    .value(ragVersion.toString()))
                    .andExpect(header().string("Cache-Control", "no-store"));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM audit_event WHERE actor_id=? AND resource_id=?"
                                + " AND action='CHAT_MESSAGES_READ' AND detail->>'reason'='隔离审计回溯'",
                            Integer.class,
                            adminId,
                            UUID.fromString(conversation)));
            mvc.perform(
                            get("/api/v1/admin/conversations/" + conversation + "/messages")
                                    .header("Authorization", "Bearer " + profilePartnerToken)
                                    .param("reason", "中介不得审计"))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get("/api/v1/admin/conversations/" + conversation + "/messages")
                                    .header("Authorization", "Bearer " + st)
                                    .param("reason", "学生不得审计"))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get("/api/v1/admin/conversations/" + conversation + "/messages")
                                    .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isBadRequest());
            mvc.perform(
                            get("/api/v1/admin/conversations/" + conversation + "/messages")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .param("reason", " "))
                    .andExpect(status().isBadRequest());

            mvc.perform(
                            delete("/api/v1/student/conversations/" + conversation)
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"2\""))
                    .andExpect(status().isPreconditionFailed());
            mvc.perform(
                            delete("/api/v1/student/conversations/" + conversation)
                                    .header("Authorization", "Bearer " + st)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\""))
                    .andExpect(status().isNoContent());
            mvc.perform(
                            get("/api/v1/student/conversations/" + conversation)
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isNotFound());
            UUID category = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " knowledge_category(id,name,code,sort_order,status,created_by,updated_by)"
                        + " VALUES(?,'项目知识',?,0,'ENABLED',?,?)",
                    category,
                    category.toString(),
                    adminId,
                    adminId);
            var kb =
                    new HashMap<String, Object>(
                            Map.of(
                                    "title",
                                    "项目私有知识" + project,
                                    "categoryId",
                                    category,
                                    "tagIds",
                                    List.of(),
                                    "type",
                                    "DOCUMENT",
                                    "audience",
                                    "PROJECT",
                                    "projectId",
                                    project));
            UUID contentId =
                    UUID.fromString(
                            json.readTree(
                                            mvc.perform(
                                                            post("/api/v1/admin/knowledge")
                                                                    .header(
                                                                            "Authorization",
                                                                            "Bearer " + adminToken)
                                                                    .header(
                                                                            "Idempotency-Key",
                                                                            UUID.randomUUID())
                                                                    .contentType("application/json")
                                                                    .content(
                                                                            json.writeValueAsString(
                                                                                    kb)))
                                                    .andExpect(status().isCreated())
                                                    .andReturn()
                                                    .getResponse()
                                                    .getContentAsString())
                                    .get("id")
                                    .asText());
            UUID docAsset = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " asset(id,upload_id,owner_type,content_id,storage_id,filename,size,sha256,mime_type,upload_key,private_key,state,upload_expires_at,created_by,updated_by)"
                        + " VALUES(?,?,'KNOWLEDGE',?,?,'资料.pdf',10,?,'application/pdf',?,?,'READY',now(),?,?)",
                    docAsset,
                    UUID.randomUUID(),
                    contentId,
                    store,
                    "0".repeat(64),
                    "zx-test/" + docAsset + "/upload",
                    "zx-test/" + docAsset + "/private",
                    adminId,
                    adminId);
            kb.put("assetId", docAsset);
            String kp = "/api/v1/admin/knowledge/" + contentId;
            mvc.perform(
                            put(kp).header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(kb)))
                    .andExpect(status().isOk());
            for (int n = 0; n < 2; n++)
                mvc.perform(
                                post(kp + (n == 0 ? "/submit-review" : "/publish"))
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .header("If-Match", "\"" + (n + 2) + "\"")
                                        .contentType("application/json")
                                        .content("{\"reason\":\"项目知识审核\"}"))
                        .andExpect(status().isOk());
            assertTrue(knowledgeIndexer.runOne());
            assertEquals(
                    "ASSET_OBJECT_MISSING",
                    db.queryForObject(
                            "SELECT error_code FROM knowledge_index_task WHERE content_id=?",
                            String.class,
                            contentId));
            mvc.perform(
                            get("/api/v1/student/knowledge/" + contentId)
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk());
            mvc.perform(
                            get("/api/v1/student/knowledge/" + contentId)
                                    .header("Authorization", "Bearer " + ot))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            post("/api/v1/student/assets/" + docAsset + "/download")
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk());
            mvc.perform(
                            post("/api/v1/student/assets/" + docAsset + "/download")
                                    .header("Authorization", "Bearer " + ot))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            post(kp + "/withdraw")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"4\"")
                                    .contentType("application/json")
                                    .content("{\"reason\":\"撤回知识\"}"))
                    .andExpect(status().isOk());
            mvc.perform(
                            post("/api/v1/student/assets/" + docAsset + "/download")
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isNotFound());
            for (int n = 1; n <= 2; n++) {
                UUID asset = UUID.randomUUID();
                // 本测试聚焦授权和版本状态，READY夹具不代表上传验证；真实上传另有独立端到端测试。
                db.update(
                        "INSERT INTO"
                            + " asset(id,upload_id,owner_type,project_id,storage_id,filename,size,sha256,mime_type,upload_key,private_key,state,upload_expires_at,created_by,updated_by)"
                            + " VALUES(?,?,'PROJECT',?,?,'源码.zip',10,?,'application/zip',?,?,'READY',now(),?,?)",
                        asset,
                        UUID.randomUUID(),
                        project,
                        store,
                        "0".repeat(64),
                        "zx-test/" + asset + "/source",
                        "zx-test/" + asset + "/private",
                        adminId,
                        adminId);
                String body =
                        json.writeValueAsString(
                                Map.of("kind", "SOURCE_CODE", "assetId", asset, "note", "版本" + n));
                var result =
                        json.readTree(
                                mvc.perform(
                                                post(adminPath)
                                                        .header(
                                                                "Authorization",
                                                                "Bearer " + adminToken)
                                                        .header(
                                                                "Idempotency-Key",
                                                                UUID.randomUUID())
                                                        .contentType("application/json")
                                                        .content(body))
                                        .andExpect(status().isCreated())
                                        .andExpect(jsonPath("$.versionNumber").value(n))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString());
                UUID delivery = UUID.fromString(result.get("id").asText());
                mvc.perform(get(studentPath).header("Authorization", "Bearer " + st))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.total").value(n - 1));
                mvc.perform(
                                post("/api/v1/student/assets/" + asset + "/download")
                                        .header("Authorization", "Bearer " + st))
                        .andExpect(status().isNotFound());
                String publish = "/api/v1/admin/deliveries/" + delivery + "/publish";
                mvc.perform(
                                post(publish)
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .header("If-Match", "\"1\"")
                                        .contentType("application/json")
                                        .content("{\"reason\":\"审核通过\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.state").value("PUBLISHED"));
                while (notifications.processOne()) {}
                mvc.perform(
                                get("/api/v1/student/notifications")
                                        .header("Authorization", "Bearer " + st)
                                        .param("q", "项目资料已更新"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.total").value(n));
                mvc.perform(get(studentPath).header("Authorization", "Bearer " + st))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.total").value(1))
                        .andExpect(jsonPath("$.items[0].versionNumber").value(n));
                mvc.perform(get(studentPath).header("Authorization", "Bearer " + ot))
                        .andExpect(status().isNotFound());
                mvc.perform(
                                post("/api/v1/student/assets/" + asset + "/download")
                                        .header("Authorization", "Bearer " + st))
                        .andExpect(status().isOk());
                mvc.perform(
                                post("/api/v1/student/assets/" + asset + "/download")
                                        .header("Authorization", "Bearer " + ot))
                        .andExpect(status().isNotFound());
                mvc.perform(
                                delete("/api/v1/admin/assets/" + asset)
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .header("If-Match", "\"1\""))
                        .andExpect(status().isConflict());
                assertThrows(
                        org.springframework.dao.DataIntegrityViolationException.class,
                        () ->
                                db.update(
                                        "UPDATE project_delivery SET note='覆盖' WHERE id=?",
                                        delivery));
                if (previous != null) {
                    assertEquals(
                            "SUPERSEDED",
                            db.queryForObject(
                                    "SELECT state FROM project_delivery WHERE id=?",
                                    String.class,
                                    previous));
                    mvc.perform(
                                    post("/api/v1/student/assets/" + previousAsset + "/download")
                                            .header("Authorization", "Bearer " + st))
                            .andExpect(status().isNotFound());
                }
                previous = delivery;
                previousAsset = asset;
            }
            // 状态机夹具：收款和绑定另有端到端测试，此处激活仅用于阶段分支。
            db.update(
                    "UPDATE software_order SET status='ACTIVE',receipt_status='CONFIRMED' WHERE"
                            + " id=?",
                    order);
            String stagePath = "/api/v1/admin/projects/" + project + "/stage";
            long pv = 1;
            mvc.perform(
                            post(stagePath)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content("{\"stage\":\"DONE\",\"reason\":\"越过验收\"}"))
                    .andExpect(status().isUnprocessableEntity());
            for (String stage :
                    List.of(
                            "REQUIREMENTS",
                            "PAUSED",
                            "REQUIREMENTS",
                            "PROPOSAL",
                            "DEVELOPMENT",
                            "DOCUMENTATION")) {
                mvc.perform(
                                post(stagePath)
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .header("If-Match", "\"" + pv + "\"")
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of("stage", stage, "reason", "阶段测试"))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.stage").value(stage));
                pv++;
            }
            mvc.perform(
                            post(stagePath)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"" + pv + "\"")
                                    .contentType("application/json")
                                    .content("{\"stage\":\"ACCEPTANCE\",\"reason\":\"尚无模板\"}"))
                    .andExpect(status().isUnprocessableEntity());
            db.update(
                    "INSERT INTO"
                        + " project_milestone(id,project_id,name,due_date,weight,completed,created_by,updated_by)"
                        + " VALUES(?,?,'验收夹具',current_date,100,true,?,?)",
                    UUID.randomUUID(),
                    project,
                    adminId,
                    adminId);
            String template =
                    json.readTree(
                                    mvc.perform(
                                                    post(adminPath)
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + adminToken)
                                                            .header(
                                                                    "Idempotency-Key",
                                                                    UUID.randomUUID())
                                                            .contentType("application/json")
                                                            .content(
                                                                    json.writeValueAsString(
                                                                            Map.of(
                                                                                    "kind",
                                                                                    "THESIS_TEMPLATE",
                                                                                    "assetId",
                                                                                    previousAsset,
                                                                                    "note",
                                                                                    "验收模板"))))
                                            .andExpect(status().isCreated())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                            .get("id")
                            .asText();
            mvc.perform(
                            post("/api/v1/admin/deliveries/" + template + "/publish")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content("{\"reason\":\"审核通过\"}"))
                    .andExpect(status().isOk());
            for (String decision : List.of("REJECT", "ACCEPT")) {
                if (decision.equals("ACCEPT")) {
                    mvc.perform(
                                    post(stagePath)
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .header("If-Match", "\"" + pv + "\"")
                                            .contentType("application/json")
                                            .content(
                                                    "{\"stage\":\"DOCUMENTATION\",\"reason\":\"修正完成\"}"))
                            .andExpect(status().isOk());
                    pv++;
                }
                mvc.perform(
                                post(stagePath)
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .header("If-Match", "\"" + pv + "\"")
                                        .contentType("application/json")
                                        .content("{\"stage\":\"ACCEPTANCE\",\"reason\":\"提交验收\"}"))
                        .andExpect(status().isOk());
                pv++;
                String acceptPath = "/api/v1/student/projects/" + project + "/acceptance",
                        body =
                                json.writeValueAsString(
                                        Map.of("decision", decision, "reason", "学生验收意见"));
                mvc.perform(
                                post(acceptPath)
                                        .header("Authorization", "Bearer " + ot)
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .header("If-Match", "\"" + pv + "\"")
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isNotFound());
                mvc.perform(
                                post(acceptPath)
                                        .header("Authorization", "Bearer " + st)
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .header("If-Match", "\"" + pv + "\"")
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.stage")
                                        .value(decision.equals("ACCEPT") ? "DONE" : "DEVELOPMENT"));
                pv++;
            }
            mvc.perform(
                            post("/api/v1/admin/deliveries/" + previous + "/withdraw")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"2\"")
                                    .contentType("application/json")
                                    .content("{\"reason\":\"撤回待复核\"}"))
                    .andExpect(status().isOk());
            mvc.perform(
                            post("/api/v1/admin/deliveries/" + template + "/withdraw")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"2\"")
                                    .contentType("application/json")
                                    .content("{\"reason\":\"撤回模板\"}"))
                    .andExpect(status().isOk());
            mvc.perform(get(studentPath).header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            mvc.perform(
                            post("/api/v1/student/assets/" + previousAsset + "/download")
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isNotFound());
        } finally {
            db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", adminId, superRole);
        }
    }

    @Test
    void knowledgePublishIndexFencingAndMembershipScope() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        UUID category = UUID.randomUUID(),
                tag = UUID.randomUUID(),
                student = UUID.randomUUID(),
                other = UUID.randomUUID();
        String st = UUID.randomUUID().toString(),
                ot = UUID.randomUUID().toString(),
                needle = "知识隔离" + UUID.randomUUID();
        seed(student, "student", st);
        seed(other, "student", ot);
        for (UUID u : List.of(student, other))
            db.update("INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')", u);
        db.update(
                "INSERT INTO"
                    + " knowledge_category(id,name,code,sort_order,status,created_by,updated_by)"
                    + " VALUES(?,'索引测试',?,0,'ENABLED',?,?)",
                category,
                category.toString(),
                adminId,
                adminId);
        db.update(
                "INSERT INTO knowledge_tag(id,name,code,status,created_by,updated_by)"
                        + " VALUES(?,'测试标签',?,'ENABLED',?,?)",
                tag,
                tag.toString(),
                adminId,
                adminId);
        try {
            var body =
                    new HashMap<String, Object>(
                            Map.of(
                                    "title",
                                    needle,
                                    "categoryId",
                                    category,
                                    "tagIds",
                                    List.of(tag),
                                    "type",
                                    "MARKDOWN",
                                    "markdown",
                                    "# 原文\n事务与幂等测试内容",
                                    "audience",
                                    "PUBLIC"));
            String path = "/api/v1/admin/knowledge";
            var created =
                    json.readTree(
                            mvc.perform(
                                            post(path)
                                                    .header("Authorization", "Bearer " + adminToken)
                                                    .header("Idempotency-Key", UUID.randomUUID())
                                                    .contentType("application/json")
                                                    .content(json.writeValueAsString(body)))
                                    .andExpect(status().isCreated())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            UUID id = UUID.fromString(created.get("id").asText());
            String item = path + "/" + id;
            mvc.perform(
                            get("/api/v1/student/knowledge/" + id)
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isNotFound());
            for (int n = 0; n < 2; n++)
                mvc.perform(
                                post(item + (n == 0 ? "/submit-review" : "/publish"))
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .header("If-Match", "\"" + (n + 1) + "\"")
                                        .contentType("application/json")
                                        .content("{\"reason\":\"审核发布\"}"))
                        .andExpect(status().isOk());
            UUID first =
                    db.queryForObject(
                            "SELECT published_version_id FROM knowledge_content WHERE id=?",
                            UUID.class,
                            id);
            mvc.perform(
                            put(item)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"3\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isUnprocessableEntity());
            var old = indexes.claim();
            assertNotNull(old);
            mvc.perform(
                            post(item + "/withdraw")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"3\"")
                                    .contentType("application/json")
                                    .content("{\"reason\":\"撤回修订\"}"))
                    .andExpect(status().isOk());
            assertFalse(knowledgeIndexer.complete(old, List.of("不应写入的过期内容"), null));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM knowledge_chunk WHERE content_id=?",
                            Integer.class,
                            id));
            mvc.perform(
                            get("/api/v1/student/knowledge-search")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", needle))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            body.put("markdown", "# 新版\n" + needle + "：事务隔离和幂等会计");
            body.put("audience", "MEMBER");
            mvc.perform(
                            put(item)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"4\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isOk());
            for (int n = 0; n < 2; n++)
                mvc.perform(
                                post(item + (n == 0 ? "/submit-review" : "/publish"))
                                        .header("Authorization", "Bearer " + adminToken)
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .header("If-Match", "\"" + (n + 5) + "\"")
                                        .contentType("application/json")
                                        .content("{\"reason\":\"再次审核发布\"}"))
                        .andExpect(status().isOk());
            assertTrue(knowledgeIndexer.runOne());
            assertEquals(
                    "READY",
                    db.queryForObject(
                            "SELECT index_state FROM knowledge_content WHERE id=?",
                            String.class,
                            id));
            assertEquals(
                    "# 原文\n事务与幂等测试内容",
                    db.queryForObject(
                            "SELECT markdown FROM knowledge_version WHERE id=?",
                            String.class,
                            first));
            assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class,
                    () ->
                            db.update(
                                    "UPDATE knowledge_version SET markdown='覆盖' WHERE id=?",
                                    first));
            mvc.perform(
                            get("/api/v1/student/knowledge-search")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", needle))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            UUID plan = UUID.randomUUID(), pv = UUID.randomUUID(), member = UUID.randomUUID();
            db.update(
                    "INSERT INTO membership_plan(id,name,code,status,created_by,updated_by)"
                            + " VALUES(?,'知识会员',?,'ENABLED',?,?)",
                    plan,
                    plan.toString(),
                    adminId,
                    adminId);
            db.update(
                    "INSERT INTO"
                        + " membership_plan_version(id,plan_id,version_number,duration_days,monthly_token_quota,monthly_amount_limit,effective_from)"
                        + " VALUES(?,?,1,30,1000,1,now())",
                    pv,
                    plan);
            db.update(
                    "INSERT INTO"
                        + " membership(id,user_id,plan_id,plan_version_id,starts_at,expires_at,created_by)"
                        + " VALUES(?,?,?,?,now()-interval '1 minute',now()+interval '1 day',?)",
                    member,
                    student,
                    plan,
                    pv,
                    adminId);
            mvc.perform(
                            get("/api/v1/student/knowledge-search")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", needle)
                                    .param("categoryId", category.toString())
                                    .param("tagId", tag.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].citations[0].sourceType").value("KNOWLEDGE"));
            mvc.perform(
                            get("/api/v1/student/knowledge/" + id + "/versions")
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2));
            mvc.perform(
                            get("/api/v1/student/knowledge/" + id + "/versions")
                                    .header("Authorization", "Bearer " + ot))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            get("/api/v1/student/knowledge-search")
                                    .header("Authorization", "Bearer " + ot)
                                    .param("q", needle))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            var rebuild =
                    mvc.perform(
                                    post(item + "/reindex")
                                            .header("Authorization", "Bearer " + adminToken)
                                            .header("Idempotency-Key", UUID.randomUUID())
                                            .header("If-Match", "\"7\"")
                                            .contentType("application/json")
                                            .content("{\"reason\":\"测试重建\"}"))
                            .andExpect(status().isAccepted())
                            .andExpect(jsonPath("$.kind").value("KNOWLEDGE_INDEX"));
            mvc.perform(
                            get("/api/v1/student/knowledge-search")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", needle))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            assertTrue(knowledgeIndexer.runOne());
            db.update("UPDATE membership SET revoked_at=now() WHERE id=?", member);
            mvc.perform(
                            get("/api/v1/student/knowledge-search")
                                    .header("Authorization", "Bearer " + st)
                                    .param("q", needle))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
            mvc.perform(
                            post(item + "/withdraw")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"8\"")
                                    .contentType("application/json")
                                    .content("{\"reason\":\"测试删除\"}"))
                    .andExpect(status().isOk());
            mvc.perform(
                            delete(item)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"9\""))
                    .andExpect(status().isNoContent());
            mvc.perform(
                            delete("/api/v1/admin/tags/" + tag)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\""))
                    .andExpect(status().isConflict());
        } finally {
            db.update("DELETE FROM user_role WHERE user_id=? AND role_id=?", adminId, superRole);
        }
    }

    @Test
    void realBrokerInboxDeduplicatesAndDeadLettersInvalidEvents() throws Exception {
        UUID event = UUID.randomUUID(), aggregate = UUID.randomUUID();
        String type = "test.activity." + UUID.randomUUID();
        db.update(
                "INSERT INTO outbox_event(id,aggregate_id,event_type,payload)"
                        + " VALUES(?,?,?,'{}'::jsonb)",
                event,
                aggregate,
                type);
        byte[] body =
                json.writeValueAsBytes(
                        Map.of(
                                "eventId",
                                event,
                                "aggregateId",
                                aggregate,
                                "type",
                                type,
                                "data",
                                Map.of()));
        var props = new org.springframework.amqp.core.MessageProperties();
        props.setMessageId(event.toString());
        props.setContentType("application/json");
        var message = new org.springframework.amqp.core.Message(body, props);
        String name = "zx.test.activity." + UUID.randomUUID(),
                dead = name + ".dead",
                exchange = name + ".dlx";
        var admin =
                new org.springframework.amqp.rabbit.core.RabbitAdmin(rabbit.getConnectionFactory());
        var queue =
                org.springframework.amqp.core.QueueBuilder.nonDurable(name)
                        .deadLetterExchange(exchange)
                        .deadLetterRoutingKey("dead")
                        .build();
        var dlq = new org.springframework.amqp.core.Queue(dead, false);
        var dlx = new org.springframework.amqp.core.DirectExchange(exchange, false, false);
        var listener =
                new org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer(
                        rabbit.getConnectionFactory());
        var handled = new java.util.concurrent.CountDownLatch(2);
        try {
            admin.declareExchange(dlx);
            admin.declareQueue(dlq);
            admin.declareQueue(queue);
            admin.declareBinding(
                    org.springframework.amqp.core.BindingBuilder.bind(dlq).to(dlx).with("dead"));
            listener.setQueueNames(name);
            listener.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.AUTO);
            listener.setDefaultRequeueRejected(false);
            listener.setConcurrentConsumers(2);
            listener.setErrorHandler(error -> {});
            listener.setMessageListener(
                    (org.springframework.amqp.core.MessageListener)
                            m -> {
                                activityInbox.consume(m);
                                handled.countDown();
                            });
            listener.start();
            rabbit.send("", name, message);
            rabbit.send("", name, message);
            assertTrue(handled.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM inbox_event WHERE event_id=? AND"
                                    + " consumer='activity-v1'",
                            Integer.class,
                            event));
            assertEquals(
                    1L,
                    db.queryForObject(
                            "SELECT sum(event_count) FROM event_activity_day WHERE event_type=?",
                            Long.class,
                            type));
            var wrongProps = new org.springframework.amqp.core.MessageProperties();
            wrongProps.setMessageId(UUID.randomUUID().toString());
            rabbit.send("", name, new org.springframework.amqp.core.Message(body, wrongProps));
            assertNotNull(rabbit.receive(dead, 5000));
            assertEquals(
                    1L,
                    db.queryForObject(
                            "SELECT sum(event_count) FROM event_activity_day WHERE event_type=?",
                            Long.class,
                            type));
        } finally {
            listener.stop();
            admin.deleteQueue(name);
            admin.deleteQueue(dead);
            admin.deleteExchange(exchange);
        }
    }

    @Test
    void quotaConcurrentReservationsUnknownSettlementAndAppendOnlyLedger() throws Exception {
        UUID student = UUID.randomUUID(),
                plan = UUID.randomUUID(),
                pv = UUID.randomUUID(),
                member = UUID.randomUUID(),
                model = UUID.randomUUID(),
                price = UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        seed(student, "student", token);
        db.update(
                "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')", student);
        assertEquals(0, quotas.current(student).availableTokens());
        db.update(
                "INSERT INTO membership_plan(id,name,code,status,created_by,updated_by)"
                        + " VALUES(?,'额度会员',?,'ENABLED',?,?)",
                plan,
                plan.toString(),
                adminId,
                adminId);
        db.update(
                "INSERT INTO"
                    + " membership_plan_version(id,plan_id,version_number,duration_days,monthly_token_quota,monthly_amount_limit,effective_from)"
                    + " VALUES(?,?,1,30,100,1,now())",
                pv,
                plan);
        db.update(
                "INSERT INTO"
                    + " membership(id,user_id,plan_id,plan_version_id,starts_at,expires_at,created_by)"
                    + " VALUES(?,?,?,?,now()-interval '1 minute',now()+interval '1 day',?)",
                member,
                student,
                plan,
                pv,
                adminId);
        db.update(
                "INSERT INTO"
                    + " model_definition(id,name,code,provider,endpoint,context_limit,max_output_tokens,status,created_by,updated_by)"
                    + " VALUES(?,'隔离模型',?,'TEST','https://model.invalid',4096,1024,'ENABLED',?,?)",
                model,
                model.toString(),
                adminId,
                adminId);
        db.update(
                "INSERT INTO"
                    + " model_price(id,model_id,currency,input_per_million,output_per_million,cached_input_per_million,effective_from,status,created_by,updated_by)"
                    + " VALUES(?,?,'CNY',10,20,2,now()-interval '1 day','ENABLED',?,?)",
                price,
                model,
                adminId,
                adminId);
        assertEquals(100, quotas.current(student).availableTokens());
        assertEquals(100, quotas.current(student).availableTokens());
        UUID quotaSchool = UUID.randomUUID(),
                quotaMajor = UUID.randomUUID(),
                quotaOrder = UUID.randomUUID(),
                quotaProject = UUID.randomUUID(),
                quotaConversation = UUID.randomUUID();
        db.update(
                "INSERT INTO school(id,name,code,region,status,created_by,updated_by)"
                        + " VALUES(?,'账务测试院校',?,'测试','ENABLED',?,?)",
                quotaSchool,
                quotaSchool.toString(),
                adminId,
                adminId);
        db.update(
                "INSERT INTO major(id,name,code,school_id,level,status,created_by,updated_by)"
                        + " VALUES(?,'账务测试专业',?,?,'BACHELOR','ENABLED',?,?)",
                quotaMajor,
                quotaMajor.toString(),
                quotaSchool,
                adminId,
                adminId);
        db.update(
                "INSERT INTO"
                    + " software_order(id,order_no,title,student_id,student_phone_digest,student_phone_ciphertext,amount,currency,package_code,source,school_id,major_id,created_by,updated_by,status)"
                    + " VALUES(?,?,'账务夹具',?,'test','test',0,'CNY','test','MANUAL',?,?,?,?,'CONFIRMED')",
                quotaOrder,
                quotaOrder.toString(),
                student,
                quotaSchool,
                quotaMajor,
                adminId,
                adminId);
        db.update(
                "INSERT INTO"
                    + " software_project(id,order_id,title,requirements,delivery_date,created_by,updated_by)"
                    + " VALUES(?,?,'账务夹具','隔离测试',CURRENT_DATE,?,?)",
                quotaProject,
                quotaOrder,
                adminId,
                adminId);
        db.update(
                "INSERT INTO chat_conversation(id,project_id,student_id,title)"
                        + " VALUES(?,?,?,'账务夹具')",
                quotaConversation,
                quotaProject,
                student);
        java.util.function.Supplier<UUID> newRun =
                () -> {
                    UUID id = UUID.randomUUID();
                    db.update(
                            "INSERT INTO"
                                + " generation_run(id,student_id,conversation_id,client_message_id,request_hash,model_id,price_id)"
                                + " VALUES(?,?,?,?,'quota-test',?,?)",
                            id,
                            student,
                            quotaConversation,
                            UUID.randomUUID(),
                            model,
                            price);
                    return id;
                };
        UUID a = newRun.get(), b = newRun.get();
        var gate = new java.util.concurrent.CountDownLatch(1);
        List<UUID> accepted = java.util.Collections.synchronizedList(new ArrayList<>());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (UUID run : List.of(a, b))
                futures.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        quotas.reserve(
                                                student,
                                                run,
                                                price,
                                                60,
                                                new java.math.BigDecimal("0.600000"));
                                        accepted.add(run);
                                        return true;
                                    } catch (
                                            cn.zhixiang.platform.shared.exception.BusinessException
                                                    e) {
                                        assertEquals("QUOTA_EXCEEDED", e.code());
                                        return false;
                                    }
                                }));
            gate.countDown();
            assertEquals(
                    1,
                    futures.stream()
                            .map(
                                    f -> {
                                        try {
                                            return f.get() ? 1 : 0;
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                    })
                            .mapToInt(Integer::intValue)
                            .sum());
        }
        UUID run = accepted.getFirst();
        quotas.reserve(student, run, price, 60, new java.math.BigDecimal("0.600000"));
        assertEquals(40, quotas.current(student).availableTokens());
        quotas.unknown(run);
        quotas.unknown(run);
        assertThrows(
                cn.zhixiang.platform.shared.exception.BusinessException.class,
                () -> quotas.release(run, false));
        assertEquals(60, quotas.current(student).reservedTokens());
        quotas.settle(run, 40, 10, 20);
        quotas.settle(run, 40, 10, 20);
        assertEquals(50, quotas.current(student).availableTokens());
        assertEquals(0, quotas.current(student).reservedTokens());
        assertEquals("0.999560", quotas.current(student).availableAmount());
        assertThrows(
                cn.zhixiang.platform.shared.exception.BusinessException.class,
                () -> quotas.settle(run, 41, 10, 20));
        UUID release = newRun.get();
        quotas.reserve(student, release, price, 30, new java.math.BigDecimal("0.100000"));
        quotas.release(release, true);
        quotas.release(release, true);
        assertEquals(50, quotas.current(student).availableTokens());
        UUID account =
                db.queryForObject(
                        "SELECT id FROM quota_account WHERE user_id=?", UUID.class, student);
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "UPDATE quota_hold SET state='SETTLED',input_tokens=NULL WHERE"
                                        + " run_id=?",
                                run));
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "INSERT INTO"
                                        + " quota_ledger(id,account_id,source_id,kind,grant_tokens)"
                                        + " VALUES(?,?,?,'GRANT',-1)",
                                UUID.randomUUID(),
                                account,
                                UUID.randomUUID()));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM quota_ledger WHERE account_id=? AND kind='GRANT'",
                        Integer.class,
                        account));
        assertEquals(
                50L,
                db.queryForObject(
                        "SELECT sum(spend_tokens) FROM quota_ledger WHERE account_id=?",
                        Long.class,
                        account));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT sum(reserve_tokens) FROM quota_ledger WHERE account_id=?",
                        Long.class,
                        account));
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                        db.update(
                                "UPDATE quota_ledger SET spend_tokens=0 WHERE account_id=?",
                                account));
        mvc.perform(get("/api/v1/student/quota").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTokens").value(50));
        // 仅把此测试学生的账户移至上月，模拟跨月迟到结算，不修改服务器时钟。
        UUID late = newRun.get();
        quotas.reserve(student, late, price, 30, new java.math.BigDecimal("0.100000"));
        db.update(
                "UPDATE quota_account SET period_start=period_start-interval '1"
                        + " month',period_end=period_start WHERE id=?",
                account);
        assertEquals(100, quotas.current(student).availableTokens());
        assertEquals(0, quotas.current(student).reservedTokens());
        quotas.unknown(late);
        quotas.settle(late, 150, 20, 0); // 超出预占也完整记账，不能向新周期转嫁费用。
        assertEquals(
                220L,
                db.queryForObject(
                        "SELECT spent_tokens FROM quota_account WHERE id=?", Long.class, account));
        assertEquals(100, quotas.current(student).availableTokens());
        db.update("UPDATE membership SET revoked_at=now() WHERE id=?", member);
        assertEquals(0, quotas.current(student).availableTokens());
        assertThrows(
                cn.zhixiang.platform.shared.exception.BusinessException.class,
                () ->
                        quotas.reserve(
                                student,
                                UUID.randomUUID(),
                                price,
                                1,
                                new java.math.BigDecimal("0.000001")));
    }

    @Test
    void realMinioSignedUploadIntegrityAndPrivateCopy() throws Exception {
        var target =
                new cn.zhixiang.platform.storage.port.ObjectStorage.Target(
                        "S3_COMPATIBLE",
                        System.getenv("ZX_S3_ENDPOINT"),
                        System.getenv("ZX_S3_REGION"),
                        "zx-quarantine-private",
                        "local-minio");
        String prefix = "zx-test/" + UUID.randomUUID(),
                key = prefix + "/upload",
                finalKey = prefix + "/private";
        byte[] data = "知项独立对象存储协议验证".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha =
                java.util.HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        var client =
                java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .build();
        cn.zhixiang.platform.storage.port.ObjectStorage.Metadata original = null, copy = null;
        try {
            var grant = storage.upload(target, key, data.length, "text/plain", sha);
            assertFalse(grant.toString().contains("X-Amz"));
            var request =
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(grant.url()))
                            .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(data));
            grant.headers()
                    .forEach(
                            (name, value) -> {
                                if (!name.equalsIgnoreCase("Content-Length"))
                                    request.header(name, value);
                            });
            assertEquals(
                    200,
                    client.send(
                                    request.build(),
                                    java.net.http.HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            original = storage.stat(target, key);
            assertEquals(data.length, original.size());
            assertEquals(sha, storage.sha256(target, key, original));
            var badRequest =
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(grant.url()))
                            .PUT(
                                    java.net.http.HttpRequest.BodyPublishers.ofByteArray(
                                            new byte[data.length]));
            grant.headers()
                    .forEach(
                            (name, value) -> {
                                if (!name.equalsIgnoreCase("Content-Length"))
                                    badRequest.header(name, value);
                            });
            int badStatus =
                    client.send(
                                    badRequest.build(),
                                    java.net.http.HttpResponse.BodyHandlers.discarding())
                            .statusCode();
            assertTrue(badStatus == 400 || badStatus == 403);
            copy = storage.promote(target, key, original, finalKey);
            assertEquals(sha, storage.sha256(target, finalKey, copy));
            var anonymous =
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create(
                                            target.endpoint()
                                                    + "/"
                                                    + target.bucket()
                                                    + "/"
                                                    + finalKey))
                            .GET()
                            .build();
            assertEquals(
                    403,
                    client.send(anonymous, java.net.http.HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            var download = storage.download(target, finalKey, copy.versionId());
            var bytes =
                    client.send(
                            java.net.http.HttpRequest.newBuilder(
                                            java.net.URI.create(download.url()))
                                    .GET()
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, bytes.statusCode());
            assertArrayEquals(data, bytes.body());
            assertThrows(
                    cn.zhixiang.platform.shared.exception.BusinessException.class,
                    () ->
                            storage.stat(
                                    new cn.zhixiang.platform.storage.port.ObjectStorage.Target(
                                            "S3_COMPATIBLE",
                                            "https://untrusted.invalid",
                                            target.region(),
                                            target.bucket(),
                                            "local-minio"),
                                    key));
        } finally {
            if (copy != null) storage.remove(target, finalKey, copy.versionId());
            if (original != null) storage.remove(target, key, original.versionId());
        }
    }

    @Test
    void projectOrderTransitionScopeAndConcurrentMilestoneWeights() throws Exception {
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        db.update("INSERT INTO user_role VALUES(?,?)", adminId, superRole);
        UUID admin2 = UUID.randomUUID();
        String token2 = UUID.randomUUID().toString();
        seed(admin2, "admin", token2);
        db.update("INSERT INTO user_role VALUES(?,?)", admin2, superRole);
        try {
            UUID school = UUID.randomUUID(), major = UUID.randomUUID(), student = UUID.randomUUID();
            String st = UUID.randomUUID().toString();
            seed(student, "student", st);
            db.update(
                    "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000002')",
                    student);
            db.update(
                    "INSERT INTO school(id,name,code,region,status,created_by,updated_by)"
                            + " VALUES(?,'项目院校',?,'浙江','ENABLED',?,?)",
                    school,
                    school.toString(),
                    adminId,
                    adminId);
            db.update(
                    "INSERT INTO major(id,name,code,school_id,level,status,created_by,updated_by)"
                            + " VALUES(?,'项目专业',?,?,'BACHELOR','ENABLED',?,?)",
                    major,
                    major.toString(),
                    school,
                    adminId,
                    adminId);
            var orderBody =
                    new HashMap<String, Object>(
                            Map.of(
                                    "title",
                                    "项目订单",
                                    "orderNo",
                                    "PROJECT-" + UUID.randomUUID(),
                                    "studentPhone",
                                    "13812341111",
                                    "amount",
                                    "100.00",
                                    "currency",
                                    "CNY",
                                    "packageCode",
                                    "STANDARD",
                                    "source",
                                    "MANUAL",
                                    "schoolId",
                                    school,
                                    "majorId",
                                    major));
            orderBody.put("partnerId", null);
            String order =
                    json.readTree(
                                    mvc.perform(
                                                    post("/api/v1/admin/orders")
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + adminToken)
                                                            .header(
                                                                    "Idempotency-Key",
                                                                    UUID.randomUUID())
                                                            .contentType("application/json")
                                                            .content(
                                                                    json.writeValueAsString(
                                                                            orderBody)))
                                            .andExpect(status().isCreated())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                            .get("id")
                            .asText();
            String path = "/api/v1/admin/projects",
                    date = java.time.LocalDate.now().plusDays(30).toString();
            var body =
                    Map.of(
                            "orderId",
                            order,
                            "title",
                            "项目题目",
                            "requirements",
                            "服务端维护的项目需求",
                            "deliveryDate",
                            date);
            mvc.perform(
                            post(path)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isUnprocessableEntity());
            mvc.perform(
                            post("/api/v1/admin/orders/" + order + "/transition")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content("{\"action\":\"CONFIRM\",\"reason\":\"已核实资料\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.receiptStatus").value("UNCONFIRMED"));
            String project =
                    json.readTree(
                                    mvc.perform(
                                                    post(path)
                                                            .header(
                                                                    "Authorization",
                                                                    "Bearer " + adminToken)
                                                            .header(
                                                                    "Idempotency-Key",
                                                                    UUID.randomUUID())
                                                            .contentType("application/json")
                                                            .content(json.writeValueAsString(body)))
                                            .andExpect(status().isCreated())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                            .get("id")
                            .asText();
            mvc.perform(
                            post(path)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isConflict());
            mvc.perform(
                            get("/api/v1/student/projects/" + project)
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isNotFound());
            UUID partner = UUID.randomUUID();
            String partnerAccess = UUID.randomUUID().toString();
            seed(partner, "partner", partnerAccess);
            db.update(
                    "INSERT INTO user_role VALUES(?,'00000000-0000-4000-8000-000000000003')",
                    partner);
            mvc.perform(
                            get("/api/v1/partner/projects/" + project + "/progress")
                                    .header("Authorization", "Bearer " + partnerAccess))
                    .andExpect(status().isNotFound());
            db.update(
                    "UPDATE software_order SET partner_id=? WHERE id=?",
                    partner,
                    UUID.fromString(order));
            mvc.perform(
                            get("/api/v1/partner/projects/" + project + "/progress")
                                    .header("Authorization", "Bearer " + partnerAccess))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requirements").doesNotExist())
                    .andExpect(jsonPath("$.milestones").isArray());
            mvc.perform(
                            get("/api/v1/partner/projects")
                                    .header("Authorization", "Bearer " + partnerAccess)
                                    .param("q", "项目题目"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].requirements").doesNotExist());
            // 绑定功能由另一完整挑战测试验证；此处仅建立项目可见性夹具。
            db.update(
                    "UPDATE software_order SET student_id=? WHERE id=?",
                    student,
                    UUID.fromString(order));
            mvc.perform(
                            get("/api/v1/student/projects/" + project)
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.progress").value(0));
            mvc.perform(
                            post("/api/v1/admin/orders/" + order + "/transition")
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"2\"")
                                    .contentType("application/json")
                                    .content("{\"action\":\"ACTIVATE\",\"reason\":\"测试缺少收款的拒绝\"}"))
                    .andExpect(status().isUnprocessableEntity());
            String milestones = path + "/" + project + "/milestones";
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var tasks = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
                for (String token : List.of(adminToken, token2))
                    tasks.add(
                            pool.submit(
                                    () -> {
                                        start.await();
                                        return mvc.perform(
                                                        post(milestones)
                                                                .header(
                                                                        "Authorization",
                                                                        "Bearer " + token)
                                                                .header(
                                                                        "Idempotency-Key",
                                                                        UUID.randomUUID())
                                                                .contentType("application/json")
                                                                .content(
                                                                        json.writeValueAsString(
                                                                                Map.of(
                                                                                        "name",
                                                                                        UUID.randomUUID()
                                                                                                .toString(),
                                                                                        "dueDate",
                                                                                        date,
                                                                                        "weight",
                                                                                        60,
                                                                                        "completed",
                                                                                        true))))
                                                .andReturn()
                                                .getResponse()
                                                .getStatus();
                                    }));
                start.countDown();
                var statuses = new java.util.ArrayList<Integer>();
                for (var f : tasks) statuses.add(f.get());
                java.util.Collections.sort(statuses);
                assertEquals(List.of(201, 422), statuses);
            }
            mvc.perform(get(path + "/" + project).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.progress").value(60))
                    .andExpect(jsonPath("$.version").value(2));
            mvc.perform(
                            put(path + "/" + project)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\"")
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isPreconditionFailed());
            var list =
                    mvc.perform(get(milestones).header("Authorization", "Bearer " + adminToken))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.total").value(1))
                            .andReturn()
                            .getResponse();
            String mid =
                    json.readTree(list.getContentAsString()).get("items").get(0).get("id").asText();
            mvc.perform(
                            delete("/api/v1/admin/milestones/" + mid)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", UUID.randomUUID())
                                    .header("If-Match", "\"1\""))
                    .andExpect(status().isNoContent());
            mvc.perform(
                            get("/api/v1/student/projects/" + project)
                                    .header("Authorization", "Bearer " + st))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.progress").value(0));
        } finally {
            db.update(
                    "DELETE FROM user_role WHERE user_id IN (?,?) AND role_id=?",
                    adminId,
                    admin2,
                    superRole);
        }
    }
}
