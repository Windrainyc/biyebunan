package cn.zhixiang.platform.operations.service;

import jakarta.annotation.PreDestroy;

import org.lionsoul.ip2region.service.Config;
import org.lionsoul.ip2region.service.Ip2Region;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/** 本地离线IP归属地查询；不向第三方发送访问地址，不将归属地解释为GPS位置。 */
@Service
public class IpLocationService {
    private final Ip2Region region;

    /**
     * 使用可选固定版本库，未配置时保持明确未知。
     *
     * @param v4 IPv4数据库路径
     * @param v6 IPv6数据库路径
     * @throws Exception 已配置数据库损坏或不兼容
     */
    public IpLocationService(
            @Value("${ZX_IP_REGION_V4:}") String v4, @Value("${ZX_IP_REGION_V6:}") String v6)
            throws Exception {
        region =
                v4.isBlank() && v6.isBlank()
                        ? null
                        : Ip2Region.create(
                                v4.isBlank()
                                        ? null
                                        : Config.custom()
                                                .setCachePolicy(Config.VIndexCache)
                                                .setSearchers(2)
                                                .setXdbPath(v4)
                                                .asV4(),
                                v6.isBlank()
                                        ? null
                                        : Config.custom()
                                                .setCachePolicy(Config.VIndexCache)
                                                .setSearchers(2)
                                                .setXdbPath(v6)
                                                .asV6());
    }

    /**
     * 查询省市和运营商，不解析主机名、不进行DNS查询。
     *
     * @param ip 服务端观测地址
     * @return 归属地或未知说明
     */
    public String locate(String ip) {
        if (ip == null || !ip.matches("[0-9a-fA-F:.]+")) return "未知";
        if (region == null) return "未知（未配置离线定位库）";
        try {
            String value = region.search(ip);
            if (value == null || value.isBlank()) return "未知";
            String[] parts = value.split("\\|");
            return Arrays.stream(parts)
                    .limit(4)
                    .filter(v -> !v.isBlank() && !v.equals("0"))
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(" · "));
        } catch (Exception ignored) {
            return "未知";
        }
    }

    /** 关闭池内文件句柄。 */
    @PreDestroy
    public void close() {
        try {
            if (region != null) region.close();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
