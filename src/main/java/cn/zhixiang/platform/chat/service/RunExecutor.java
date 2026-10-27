package cn.zhixiang.platform.chat.service;

import cn.zhixiang.platform.chat.port.ModelGateway;

import org.springframework.stereotype.Service;

import java.util.UUID;

/** 事务外模型调用协调器，每个运行最多发起一次；未知结果交给核对而不是自动重试。 */
@Service
public class RunExecutor {
    private final UUID owner = UUID.randomUUID();
    private final RunLeaseService leases;
    private final RunExecution execution;
    private final RunCompletionService completion;
    private final ModelGateway gateway;
    private final cn.zhixiang.platform.shared.observability.Telemetry telemetry;

    /**
     * 创建执行器。
     *
     * @param telemetry 运行链路上下文
     * @param leases 领取/起点
     * @param execution 上下文和增量
     * @param completion 结束事务
     * @param gateway 外部HTTP
     */
    public RunExecutor(
            cn.zhixiang.platform.shared.observability.Telemetry telemetry,
            RunLeaseService leases,
            RunExecution execution,
            RunCompletionService completion,
            ModelGateway gateway) {
        this.telemetry = telemetry;
        this.leases = leases;
        this.execution = execution;
        this.completion = completion;
        this.gateway = gateway;
    }

    /**
     * 处理至多一个任务；无运行返回false，基础设施异常抛出并保留数据库断点。
     *
     * @return 是否领取了任务
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public boolean runOne() {
        var lease = leases.claim(owner);
        if (lease == null) return false;
        try (var trace =
                telemetry.start("model.execute", lease.traceparent(), lease.id().toString())) {
            try {
                return execute(lease, trace);
            } catch (RuntimeException ex) {
                trace.error("RUN_INFRASTRUCTURE_FAILED");
                throw ex;
            }
        }
    }

    private boolean execute(
            cn.zhixiang.platform.chat.entity.RunLeaseLeaseEntity lease,
            cn.zhixiang.platform.shared.observability.Telemetry.TraceScope trace) {
        ModelGateway.Request request;
        try {
            request = execution.prepare(lease.id());
        } catch (ModelGateway.Failure failure) {
            trace.error(failure.code());
            completion.rejectBeforeCall(lease.id(), owner, lease.generation(), failure.code());
            return true;
        }
        if (!leases.start(lease.id(), owner, lease.generation())) return true;
        var output = new StringBuilder();
        try {
            var usage =
                    gateway.stream(
                            request,
                            part -> {
                                if (output.length() + part.length() > 262144)
                                    throw new ModelGateway.Failure("MODEL_OUTPUT_LIMIT", false);
                                output.append(part);
                                for (int offset = 0; offset < part.length(); ) {
                                    int end =
                                            part.offsetByCodePoints(
                                                    offset,
                                                    Math.min(
                                                            500,
                                                            part.codePointCount(
                                                                    offset, part.length())));
                                    execution.delta(
                                            lease.id(),
                                            owner,
                                            lease.generation(),
                                            part.substring(offset, end));
                                    offset = end;
                                }
                            });
            completion.success(lease.id(), owner, lease.generation(), output.toString(), usage);
        } catch (ModelGateway.Failure failure) {
            trace.error(failure.code());
            completion.failure(lease.id(), owner, lease.generation(), failure);
        }
        return true;
    }
}
