package cn.zhixiang.platform.storage.adapter;

import cn.zhixiang.platform.configuration.mapper.StorageMapper;
import cn.zhixiang.platform.configuration.mapper.StorageSecretMapper;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.security.SecretCipher;
import cn.zhixiang.platform.storage.port.ObjectStorage;

import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.*;

import org.springframework.stereotype.Component;

import java.io.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** 官方OSS V4适配器；仅固定区域官方HTTPS域名，版本固定、私有加密写入。 */
@Component
public class OssObjectStorage implements ObjectStorage {
    private final StorageMapper stores;
    private final StorageSecretMapper secrets;
    private final SecretCipher cipher;

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param stores 配置
     * @param secrets 密文
     * @param cipher 用途绑定加密
     */
    public OssObjectStorage(
            StorageMapper stores, StorageSecretMapper secrets, SecretCipher cipher) {
        this.stores = stores;
        this.secrets = secrets;
        this.cipher = cipher;
    }

    private OSS client(Target t) {
        validate(t);
        UUID id;
        try {
            id = UUID.fromString(t.credentialRef().replaceFirst("^oss-", ""));
        } catch (Exception e) {
            throw unavailable();
        }
        var cfg = stores.get(id);
        var sec = secrets.get(id);
        if (cfg == null
                || sec == null
                || !cfg.bucket().equals(t.bucket())
                || !cfg.endpoint().equals(t.endpoint())
                || !cfg.region().equals(t.region())
                || !cfg.provider().name().equals(t.provider())) throw unavailable();
        var c = new ClientBuilderConfiguration();
        c.setSignatureVersion(SignVersion.V4);
        c.setConnectionTimeout(5000);
        c.setSocketTimeout(60000);
        c.setMaxErrorRetry(0);
        return OSSClientBuilder.create()
                .endpoint(t.endpoint())
                .region(t.region())
                .credentialsProvider(
                        new DefaultCredentialProvider(
                                cipher.decrypt("oss-access:" + id, sec.accessCipher()),
                                cipher.decrypt("oss-secret:" + id, sec.secretCipher())))
                .clientConfiguration(c)
                .build();
    }

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param t 官方配置
     * @throws BusinessException 非官方地址
     */
    public static void validate(Target t) {
        if (!"ALIYUN_OSS".equals(t.provider())
                || t.region() == null
                || !t.region().matches("[a-z]{2}-[a-z0-9]+(?:-[a-z0-9]+)*")
                || !t.endpoint().equals("https://oss-" + t.region() + ".aliyuncs.com")
                || !t.bucket().matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))
            throw new BusinessException(422, "OSS_ENDPOINT_INVALID", "仅支持区域对应的阿里云OSS官方HTTPS地址");
    }

    private void key(String key) {
        if (key == null
                || key.isBlank()
                || key.startsWith("/")
                || key.contains("..")
                || key.length() > 512)
            throw new BusinessException(422, "STORAGE_KEY_INVALID", "对象键不合法");
    }

    private <T> T call(Target t, Function<OSS, T> f) {
        OSS c = client(t);
        try {
            return f.apply(c);
        } catch (BusinessException e) {
            throw e;
        } catch (OSSException e) {
            if ("NoSuchKey".equals(e.getErrorCode()) || "NoSuchVersion".equals(e.getErrorCode()))
                throw new BusinessException(404, "ASSET_OBJECT_MISSING", "存储对象不存在");
            throw unavailable();
        } catch (Exception e) {
            throw unavailable();
        } finally {
            c.shutdown();
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(503, "STORAGE_UNAVAILABLE", "OSS访问失败，请检查凭证权限、区域与存储配置");
    }

    private Metadata meta(ObjectMetadata m) {
        return new Metadata(
                m.getContentLength(), m.getETag(), m.getVersionId(), m.getContentType());
    }

    /** {@inheritDoc} */
    @Override
    public Grant upload(Target t, String k, long size, String mime, String sha) {
        key(k);
        if (size < 1
                || size > 2147483648L
                || sha == null
                || !sha.matches("[a-f0-9]{64}")
                || mime == null
                || !mime.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))
            throw new BusinessException(422, "UPLOAD_INVALID", "上传参数不合法");
        return call(
                t,
                c -> {
                    var r = new GeneratePresignedUrlRequest(t.bucket(), k, HttpMethod.PUT);
                    var until = OffsetDateTime.now().plusSeconds(300);
                    r.setExpiration(Date.from(until.toInstant()));
                    r.setContentType(mime);
                    var headers =
                            Map.of(
                                    "Content-Type",
                                    mime,
                                    "x-oss-server-side-encryption",
                                    "AES256",
                                    "x-oss-object-acl",
                                    "private",
                                    "x-oss-meta-sha256",
                                    sha);
                    r.setHeaders(new HashMap<>(headers));
                    return new Grant(c.generatePresignedUrl(r).toString(), headers, until);
                });
    }

    /** {@inheritDoc} */
    @Override
    public Metadata write(Target t, String k, byte[] bytes, String mime) {
        if (bytes == null || bytes.length > 20971520)
            throw new BusinessException(422, "EXPORT_SIZE_INVALID", "报告超出限制");
        return writeStream(t, k, new ByteArrayInputStream(bytes), bytes.length, mime);
    }

    /** {@inheritDoc} */
    @Override
    public Metadata writeStream(Target t, String k, InputStream in, long size, String mime) {
        key(k);
        if (size < 1 || size > 2147483648L)
            throw new BusinessException(422, "UPLOAD_INVALID", "对象大小超出限制");
        return call(
                t,
                c -> {
                    var m = new ObjectMetadata();
                    m.setContentLength(size);
                    m.setContentType(mime);
                    m.setServerSideEncryption("AES256");
                    m.setObjectAcl(CannedAccessControlList.Private);
                    m.setHeader("x-oss-forbid-overwrite", "true");
                    c.putObject(t.bucket(), k, in, m);
                    return meta(c.getObjectMetadata(t.bucket(), k));
                });
    }

    /** {@inheritDoc} */
    @Override
    public void probe(Target t) {
        call(
                t,
                c -> {
                    if (c.getBucketAcl(t.bucket()).getCannedACL() != CannedAccessControlList.Private
                            || !c.getBucketPublicAccessBlock(t.bucket()).getBlockPublicAccess()
                            || !"Enabled".equals(c.getBucketVersioning(t.bucket()).getStatus())
                            || !"AES256"
                                    .equals(
                                            c.getBucketEncryption(t.bucket())
                                                    .getApplyServerSideEncryptionByDefault()
                                                    .getSSEAlgorithm()))
                        throw new BusinessException(
                                422, "OSS_SECURITY_REQUIRED", "请开启私有ACL、阻止公共访问、版本控制及OSS托管AES256加密");
                    return true;
                });
    }

    /** {@inheritDoc} */
    @Override
    public Metadata stat(Target t, String k) {
        key(k);
        return call(t, c -> meta(c.getObjectMetadata(t.bucket(), k)));
    }

    /** {@inheritDoc} */
    @Override
    public String sha256(Target t, String k, Metadata expected) {
        try (var in = open(t, k, expected)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            long total = 0;
            for (int n; (n = in.read(buf)) != -1; ) {
                total += n;
                if (total > expected.size()) throw unavailable();
                digest.update(buf, 0, n);
            }
            if (total != expected.size()) throw unavailable();
            return HexFormat.of().formatHex(digest.digest());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable();
        }
    }

    /** {@inheritDoc} */
    @Override
    public Metadata promote(Target t, String src, Metadata expected, String dst) {
        key(src);
        key(dst);
        return call(
                t,
                c -> {
                    var r = new CopyObjectRequest(t.bucket(), src, t.bucket(), dst);
                    r.setSourceVersionId(expected.versionId());
                    r.setMatchingETagConstraints(List.of(expected.etag()));
                    r.setServerSideEncryption("AES256");
                    r.addHeader("x-oss-forbid-overwrite", "true");
                    r.addHeader("x-oss-object-acl", "private");
                    c.copyObject(r);
                    return meta(c.getObjectMetadata(t.bucket(), dst));
                });
    }

    /** {@inheritDoc} */
    @Override
    public Grant download(Target t, String k, String version) {
        key(k);
        return call(
                t,
                c -> {
                    var r = new GeneratePresignedUrlRequest(t.bucket(), k, HttpMethod.GET);
                    var until = OffsetDateTime.now().plusSeconds(60);
                    r.setExpiration(Date.from(until.toInstant()));
                    if (version != null) r.addQueryParameter("versionId", version);
                    return new Grant(c.generatePresignedUrl(r).toString(), Map.of(), until);
                });
    }

    /** {@inheritDoc} */
    @Override
    public Grant thumbnail(Target t, String k, String version, int width) {
        key(k);
        if (!Set.of(240, 800, 1600).contains(width) || !k.contains("/verified/") || version == null)
            throw new BusinessException(422, "THUMBNAIL_UNSUPPORTED", "该文件不支持缩略图");
        return call(
                t,
                c -> {
                    // OSS图片处理忽略versionId；仅允许服务端唯一且不再覆盖的验证键，并核对当前版本。
                    var latest = c.getObjectMetadata(t.bucket(), k);
                    if (!version.equals(latest.getVersionId()))
                        throw new BusinessException(409, "ASSET_VERSION_CHANGED", "源文件版本已变化");
                    var r = new GeneratePresignedUrlRequest(t.bucket(), k, HttpMethod.GET);
                    var until = OffsetDateTime.now().plusSeconds(60);
                    r.setExpiration(Date.from(until.toInstant()));
                    r.setProcess(
                            "image/resize,m_lfit,w_" + width + ",limit_1/format,webp/quality,q_80");
                    return new Grant(c.generatePresignedUrl(r).toString(), Map.of(), until);
                });
    }

    /** {@inheritDoc} */
    @Override
    public void remove(Target t, String k, String version) {
        key(k);
        call(
                t,
                c -> {
                    if (version == null) c.deleteObject(t.bucket(), k);
                    else c.deleteVersion(t.bucket(), k, version);
                    return true;
                });
    }

    /** {@inheritDoc} */
    @Override
    public boolean purge(Target t, String k) {
        key(k);
        return call(
                t,
                c -> {
                    var r = new ListVersionsRequest(t.bucket(), k, null, null, null, 100);
                    r.setPrefix(k);
                    r.setMaxResults(100);
                    var versions = c.listVersions(r);
                    for (var v : versions.getVersionSummaries())
                        if (k.equals(v.getKey())) c.deleteVersion(t.bucket(), k, v.getVersionId());
                    return !versions.isTruncated();
                });
    }

    /** {@inheritDoc} */
    @Override
    public InputStream open(Target t, String k, Metadata expected) {
        key(k);
        OSS c = client(t);
        try {
            var r = new GetObjectRequest(t.bucket(), k);
            r.setVersionId(expected.versionId());
            r.setMatchingETagConstraints(List.of(expected.etag()));
            var object = c.getObject(r);
            return new FilterInputStream(object.getObjectContent()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        c.shutdown();
                    }
                }
            };
        } catch (Exception e) {
            c.shutdown();
            throw unavailable();
        }
    }
}
