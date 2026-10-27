package cn.zhixiang.platform.shared.observability;

import io.micrometer.common.KeyValues;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationConvention;

/** HTTP观测只保留低基数方法、状态和路由模板，不导出完整URL、查询理由或用户输入。 */
@Configuration
public class SafeHttpObservationConfiguration {
    /** 创建不读取业务数据的观测配置。 */
    public SafeHttpObservationConfiguration() {}

    /**
     * 去除完整URL等高基数属性，避免查询参数进入外部遥测。
     *
     * @return 固定安全的服务端约定
     */
    @Bean
    public ServerRequestObservationConvention safeServerObservations() {
        return new DefaultServerRequestObservationConvention() {
            @Override
            public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext context) {
                return KeyValues.empty();
            }
        };
    }
}
