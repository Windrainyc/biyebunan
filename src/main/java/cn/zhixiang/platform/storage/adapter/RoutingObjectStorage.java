package cn.zhixiang.platform.storage.adapter;

import cn.zhixiang.platform.storage.port.ObjectStorage;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 按资产保存的供应商分派，切换默认上传不改变历史文件。 */
@Component
@Primary
public class RoutingObjectStorage implements ObjectStorage {
    private final S3ObjectStorage s3;
    private final OssObjectStorage oss;

    /**
     * 按存储配置的权限、版本与生命周期约束执行操作。
     *
     * @param s3 原存储
     * @param oss 阿里云存储
     */
    public RoutingObjectStorage(S3ObjectStorage s3, OssObjectStorage oss) {
        this.s3 = s3;
        this.oss = oss;
    }

    private ObjectStorage adapter(Target t) {
        return "ALIYUN_OSS".equals(t.provider()) ? oss : s3;
    }

    /** {@inheritDoc} */
    @Override
    public Grant upload(Target t, String k, long size, String mime, String sha) {
        return adapter(t).upload(t, k, size, mime, sha);
    }

    /** {@inheritDoc} */
    @Override
    public Metadata write(Target t, String k, byte[] b, String m) {
        return adapter(t).write(t, k, b, m);
    }

    /** {@inheritDoc} */
    @Override
    public Metadata writeStream(Target t, String k, java.io.InputStream i, long s, String m) {
        return adapter(t).writeStream(t, k, i, s, m);
    }

    /** {@inheritDoc} */
    @Override
    public void probe(Target t) {
        adapter(t).probe(t);
    }

    /** {@inheritDoc} */
    @Override
    public Metadata stat(Target t, String k) {
        return adapter(t).stat(t, k);
    }

    /** {@inheritDoc} */
    @Override
    public String sha256(Target t, String k, Metadata m) {
        return adapter(t).sha256(t, k, m);
    }

    /** {@inheritDoc} */
    @Override
    public Metadata promote(Target t, String k, Metadata m, String d) {
        return adapter(t).promote(t, k, m, d);
    }

    /** {@inheritDoc} */
    @Override
    public Grant download(Target t, String k, String v) {
        return adapter(t).download(t, k, v);
    }

    /** {@inheritDoc} */
    @Override
    public void remove(Target t, String k, String v) {
        adapter(t).remove(t, k, v);
    }

    /** {@inheritDoc} */
    @Override
    public boolean purge(Target t, String k) {
        return adapter(t).purge(t, k);
    }

    /** {@inheritDoc} */
    @Override
    public java.io.InputStream open(Target t, String k, Metadata m) {
        return adapter(t).open(t, k, m);
    }
}
