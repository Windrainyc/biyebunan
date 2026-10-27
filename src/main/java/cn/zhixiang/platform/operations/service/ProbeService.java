package cn.zhixiang.platform.operations.service;

import cn.zhixiang.platform.operations.dto.ProbeResponse;

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 复用实际Actuator健康组，只返回汇总，不暴露依赖拓扑或错误细节。 */
@Service
@Profile("!migrate & !worker & !bootstrap")
public class ProbeService {
    private final HealthEndpoint health;

    /**
     * 构造健康组适配器。
     *
     * @param health Actuator真实健康组
     */
    public ProbeService(HealthEndpoint health) {
        this.health = health;
    }

    /**
     * 读取存活组，不把数据库故障当进程死亡。
     *
     * @return 存活状态
     */
    public ProbeResponse live() {
        return group("liveness");
    }

    /**
     * 读取readinessState、数据库、Redis及RabbitMQ聚合结果。
     *
     * @return 是否允许接收业务流量
     */
    public ProbeResponse ready() {
        return group("readiness");
    }

    private ProbeResponse group(String name) {
        try {
            var result = health.healthForPath(name);
            return new ProbeResponse(
                    result != null && Status.UP.equals(result.getStatus()) ? "UP" : "DOWN");
        } catch (RuntimeException e) {
            return new ProbeResponse("DOWN");
        }
    }
}
