package cn.zhixiang.platform.shared.service;

import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 独立迁移进程：Flyway 初始化成功后退出，不启动 API 或后台调度。 */
@Component
@Profile("migrate")
public class MigrationRunner implements ApplicationRunner {
    private final ConfigurableApplicationContext context;

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param context 当前非 Web 容器
     */
    public MigrationRunner(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /**
     * 执行专用启动任务，结束时关闭当前非Web应用容器。
     *
     * @param args 启动参数；不读取敏感命令行值
     */
    @Override
    public void run(ApplicationArguments args) {
        context.close();
    }
}
