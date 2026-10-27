package cn.zhixiang.platform.operations.cli;

import cn.zhixiang.platform.operations.service.DeadLetterRecovery;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 离线单事件恢复命令；仅显式replay-dead profile启动，不在API或worker自动执行。 */
@Component
@Profile("replay-dead")
public class DeadLetterReplayCommand implements ApplicationRunner {
    private final DeadLetterRecovery recovery;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    /**
     * 创建显式运维入口。
     *
     * @param recovery 原字节恢复协调器
     * @param environment 受保护环境配置，不回显令牌
     * @param context 完成后关闭应用
     */
    public DeadLetterReplayCommand(
            DeadLetterRecovery recovery,
            Environment environment,
            ConfigurableApplicationContext context) {
        this.recovery = recovery;
        this.environment = environment;
        this.context = context;
    }

    /**
     * 从环境加载事件、会话和原因，仅成功提交目标事件才正常结束。
     *
     * @param args 不接受含凭证的命令行参数
     */
    @Override
    public void run(ApplicationArguments args) {
        UUID event = UUID.fromString(environment.getRequiredProperty("ZX_REPLAY_EVENT_ID"));
        boolean found =
                recovery.recover(
                        "zx.activity.dead",
                        event,
                        environment.getRequiredProperty("ZX_REPLAY_ACCESS_TOKEN"),
                        environment.getRequiredProperty("ZX_REPLAY_REASON"));
        if (!found) throw new IllegalStateException("前100条死信中未找到目标，请检查队列和事件标识；未删除任何其他消息");
        context.close();
    }
}
