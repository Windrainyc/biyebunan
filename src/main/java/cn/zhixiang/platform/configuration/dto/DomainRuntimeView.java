package cn.zhixiang.platform.configuration.dto;

import java.util.List;

/**
 * 当前进程的域名运行设置，不包含密钥，也不把配置存在解释为网络可用。
 *
 * @param endpoints 三端实际登录来源
 * @param probesEnabled 是否允许外部域名探测
 */
public record DomainRuntimeView(List<Endpoint> endpoints, boolean probesEnabled) {
    /**
     * 平台入口。
     *
     * @param platform 服务端平台标识
     * @param origin 当前进程实际使用的来源
     * @param probeAllowed 此主机是否在精确探测白名单中
     */
    public record Endpoint(String platform, String origin, boolean probeAllowed) {}
}
