package cn.zhixiang.platform.configuration.service;

import cn.zhixiang.platform.configuration.dto.DomainRuntimeView;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 读取正在运行的精确Origin与探测开关，保存域名记录不改变这些部署设置。 */
@Service
public class DomainRuntimeService {
    private final Environment environment;

    /**
     * 绑定当前进程配置。
     *
     * @param environment 当前进程配置，仅按固定白名单读取公开设置
     */
    public DomainRuntimeService(Environment environment) {
        this.environment = environment;
    }

    /**
     * 读取固定公开配置项。
     *
     * @return 当前进程公开入口；不会发起网络请求或申请证书
     */
    public DomainRuntimeView get() {
        boolean enabled = environment.getProperty("ZX_CONFIG_PROBES_ENABLED", Boolean.class, false);
        Set<String> hosts =
                Arrays.stream(environment.getProperty("ZX_PROBE_ALLOWED_HOSTS", "").split(","))
                        .collect(Collectors.toSet());
        var endpoints =
                List.of("student", "admin", "partner").stream()
                        .map(
                                platform -> {
                                    String origin =
                                            environment.getProperty(
                                                    "ZX_"
                                                            + platform.toUpperCase(
                                                                    java.util.Locale.ROOT)
                                                            + "_ORIGIN",
                                                    "https://" + platform + ".invalid");
                                    String host;
                                    try {
                                        host = URI.create(origin).getHost();
                                    } catch (IllegalArgumentException error) {
                                        host = null;
                                    }
                                    return new DomainRuntimeView.Endpoint(
                                            platform, origin, enabled && hosts.contains(host));
                                })
                        .toList();
        return new DomainRuntimeView(endpoints, enabled);
    }
}
