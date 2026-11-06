package cn.zhixiang.platform;

import static org.junit.jupiter.api.Assertions.*;

import cn.zhixiang.platform.forum.dto.PostDtos;
import cn.zhixiang.platform.knowledge.service.QuestionKeywords;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.storage.adapter.OssObjectStorage;
import cn.zhixiang.platform.storage.port.ObjectStorage;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;

class OssAndReviewBoundaryTest {
    @Test
    void rejectsNonOfficialAndCrossRegionEndpoints() {
        for (String endpoint :
                java.util.List.of(
                        "http://oss-cn-beijing.aliyuncs.com",
                        "https://oss-cn-beijing.aliyuncs.com.evil.test",
                        "https://127.0.0.1",
                        "https://oss-cn-shanghai.aliyuncs.com",
                        "https://oss-cn-beijing.aliyuncs.com/path"))
            assertThrows(
                    BusinessException.class,
                    () ->
                            OssObjectStorage.validate(
                                    new ObjectStorage.Target(
                                            "ALIYUN_OSS",
                                            endpoint,
                                            "cn-beijing",
                                            "private-test",
                                            "oss-test")));
        assertDoesNotThrow(
                () ->
                        OssObjectStorage.validate(
                                new ObjectStorage.Target(
                                        "ALIYUN_OSS",
                                        "https://oss-cn-beijing.aliyuncs.com",
                                        "cn-beijing",
                                        "private-test",
                                        "oss-test")));
    }

    @Test
    void postReviewAllowsMissingReasonButLimitsSize() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new PostDtos.Moderation("APPROVE", null)).isEmpty());
            assertTrue(validator.validate(new PostDtos.Moderation("HIDE", "")).isEmpty());
            assertFalse(
                    validator
                            .validate(new PostDtos.Moderation("APPROVE", "x".repeat(201)))
                            .isEmpty());
        }
    }

    @Test
    void questionTermsAreBoundedAndContainNoWildcardOrSqlSyntax() {
        var terms = QuestionKeywords.terms("请问如何部署Vue项目，配置数据库连接？%' OR 1=1 --");
        assertTrue(terms.contains("数据库".substring(0, 2)));
        assertFalse(terms.stream().anyMatch(t -> t.contains("%") || t.contains("'")));
        assertTrue(QuestionKeywords.terms("数据库".repeat(1000)).size() <= 24);
    }

    @Test
    void ossPresignsPrivateEncryptedV4UploadsWithoutNetwork() {
        var stores =
                org.mockito.Mockito.mock(
                        cn.zhixiang.platform.configuration.mapper.StorageMapper.class);
        var secrets =
                org.mockito.Mockito.mock(
                        cn.zhixiang.platform.configuration.mapper.StorageSecretMapper.class);
        var cipher =
                new cn.zhixiang.platform.shared.security.SecretCipher(
                        "isolated-test-master-key-only-1234567890");
        var id = java.util.UUID.randomUUID();
        var now = java.time.OffsetDateTime.now();
        var cfg =
                new cn.zhixiang.platform.configuration.entity.StorageEntity(
                        id,
                        1,
                        now,
                        now,
                        "test",
                        cn.zhixiang.platform.configuration.dto.StorageDtos.Provider.ALIYUN_OSS,
                        "private-test",
                        "cn-beijing",
                        "https://oss-cn-beijing.aliyuncs.com",
                        "oss-" + id,
                        cn.zhixiang.platform.configuration.dto.StorageDtos.Access.PRIVATE,
                        cn.zhixiang.platform.configuration.dto.StorageDtos.Status.ENABLED);
        org.mockito.Mockito.when(stores.get(id)).thenReturn(cfg);
        org.mockito.Mockito.when(secrets.get(id))
                .thenReturn(
                        new cn.zhixiang.platform.configuration.entity.StorageSecretEntity(
                                id,
                                1,
                                cipher.encrypt("oss-access:" + id, "isolated-ak"),
                                cipher.encrypt("oss-secret:" + id, "isolated-sk"),
                                "d-ak",
                                null,
                                null));
        var oss = new OssObjectStorage(stores, secrets, cipher);
        var target =
                new ObjectStorage.Target(
                        "ALIYUN_OSS",
                        cfg.endpoint(),
                        cfg.region(),
                        cfg.bucket(),
                        cfg.credentialRef());
        var upload = oss.upload(target, "quarantine/isolated", 5, "text/plain", "a".repeat(64));
        assertTrue(upload.url().contains("x-oss-signature-version=OSS4-HMAC-SHA256"));
        assertFalse(upload.url().contains("isolated-sk"));
        assertEquals("AES256", upload.headers().get("x-oss-server-side-encryption"));
        assertEquals("private", upload.headers().get("x-oss-object-acl"));
        var download = oss.download(target, "private/isolated", "fixed-version");
        assertTrue(download.url().contains("versionId=fixed-version"));
        assertTrue(download.expiresAt().isBefore(now.plusSeconds(65)));
    }
}
