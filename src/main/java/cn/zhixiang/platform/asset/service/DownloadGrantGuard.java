package cn.zhixiang.platform.asset.service;

import cn.zhixiang.platform.asset.mapper.DownloadGrantMapper;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** 限制下载授权签发；签名有效期内的重复 OSS 请求仍需存储或边缘侧流量防护。 */
@Service
public class DownloadGrantGuard {
    private final DownloadGrantMapper mapper;
    private final int accountMinute, networkMinute, accountDay;

    /**
     * 配置所有实例共享的签发上限。
     *
     * @param mapper 原子计数
     * @param accountMinute 单账号每分钟次数
     * @param networkMinute 同网络来源每分钟次数
     * @param accountDay 单账号每天次数
     */
    public DownloadGrantGuard(
            DownloadGrantMapper mapper,
            @Value("${ZX_DOWNLOAD_ACCOUNT_MINUTE:60}") int accountMinute,
            @Value("${ZX_DOWNLOAD_NETWORK_MINUTE:180}") int networkMinute,
            @Value("${ZX_DOWNLOAD_ACCOUNT_DAY:1000}") int accountDay) {
        if (accountMinute < 1 || networkMinute < 1 || accountDay < 1)
            throw new IllegalArgumentException("下载授权限额必须为正数");
        this.mapper = mapper;
        this.accountMinute = accountMinute;
        this.networkMinute = networkMinute;
        this.accountDay = accountDay;
    }

    /**
     * 必须在业务授权通过后调用；任一额度不足，整个事务回滚。
     *
     * @param account 当前已认证账号
     * @param address 服务器解析的来源地址，不直接信任客户端转发请求头
     */
    @Transactional
    public void acquire(UUID account, String address) {
        long now = Instant.now().getEpochSecond();
        mapper.prune();
        take("account-minute:" + account, now / 60, accountMinute, 120);
        take("network-minute:" + digest(address), now / 60, networkMinute, 120);
        take("account-day:" + account, now / 86400, accountDay, 172800);
    }

    private void take(String key, long window, int limit, int retention) {
        if (mapper.acquire(key, window, limit, retention) == null)
            throw new BusinessException(429, "DOWNLOAD_RATE_LIMITED", "下载请求过于频繁，请稍后重试");
    }

    private String digest(String address) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (address == null ? "unknown" : address)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
