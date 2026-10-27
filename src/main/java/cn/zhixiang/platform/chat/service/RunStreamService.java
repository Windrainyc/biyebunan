package cn.zhixiang.platform.chat.service;

import cn.zhixiang.platform.identity.mapper.IdentityMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.exception.BusinessException;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 有界SSE订阅器；只读数据库事件，不创建运行、不调用供应商、不改账本。 */
@Service
public class RunStreamService {
    private final RunSourceAccess sources;
    private final RunStreamAccess access;
    private final IdentityMapper identities;
    private final RunJournal journal;
    private final JsonMapper json;
    private final Semaphore slots = new Semaphore(64);
    private final ConcurrentMap<SseEmitter, Runnable> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor =
            Executors.newScheduledThreadPool(
                    4, Thread.ofPlatform().daemon().name("run-stream-", 0).factory());

    /**
     * 创建订阅服务。
     *
     * @param sources 来源范围
     * @param access 数据范围
     * @param identities 动态权限
     * @param journal 持久日志
     * @param json JSON
     */
    public RunStreamService(
            RunSourceAccess sources,
            RunStreamAccess access,
            IdentityMapper identities,
            RunJournal journal,
            JsonMapper json) {
        this.sources = sources;
        this.access = access;
        this.identities = identities;
        this.journal = journal;
        this.json = json;
    }

    /**
     * 打开最多30秒的订阅，客户端携带最后ID重连；首次错误在HTTP头提交之前返回。
     *
     * @param run 运行
     * @param lastEventId 恢复游标
     * @param actor 已认证学生
     * @return SSE发送器
     * @throws BusinessException 越权、过期游标或连接数达到上限
     */
    public SseEmitter open(UUID run, String lastEventId, Actor actor) {
        check(run, actor);
        long after = journal.cursor(run, lastEventId);
        journal.replay(run, after, 100);
        if (!slots.tryAcquire())
            throw new BusinessException(429, "STREAM_CONNECTION_LIMIT", "当前流连接过多，请稍后重试");
        var emitter = new SseEmitter(30000L);
        var cursor = new AtomicLong(after);
        var stopped = new AtomicBoolean();
        var future = new AtomicReference<ScheduledFuture<?>>();
        long started = System.nanoTime();
        var lastHeartbeat = new AtomicLong(started);
        Runnable cleanup =
                () -> {
                    if (stopped.compareAndSet(false, true)) {
                        slots.release();
                        active.remove(emitter);
                        var scheduled = future.get();
                        if (scheduled != null) scheduled.cancel(false);
                    }
                };
        active.put(emitter, cleanup);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(
                () -> {
                    cleanup.run();
                    emitter.complete();
                });
        emitter.onError(e -> cleanup.run());
        Runnable poll =
                () -> {
                    if (stopped.get()) return;
                    try {
                        if (System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(29)) {
                            cleanup.run();
                            emitter.complete();
                            return;
                        }
                        check(run, actor);
                        for (var event : journal.replay(run, cursor.get(), 100)) {
                            var data =
                                    Map.of(
                                            "runId",
                                            run,
                                            "sequence",
                                            event.sequence(),
                                            "type",
                                            event.type(),
                                            "data",
                                            json.readTree(event.payload()));
                            emitter.send(
                                    SseEmitter.event()
                                            .id(run + ":" + event.sequence())
                                            .name(event.type())
                                            .data(data));
                            cursor.set(event.sequence());
                            if (Set.of("completed", "failed", "cancelled").contains(event.type())) {
                                cleanup.run();
                                emitter.complete();
                                return;
                            }
                        }
                        if (journal.complete(run, cursor.get())) {
                            cleanup.run();
                            emitter.complete();
                            return;
                        }
                        long now = System.nanoTime();
                        if (now - lastHeartbeat.get() >= TimeUnit.SECONDS.toNanos(15)) {
                            emitter.send(SseEmitter.event().comment("heartbeat"));
                            lastHeartbeat.set(now);
                        }
                    } catch (Exception e) {
                        cleanup.run();
                        emitter.complete();
                    }
                };
        try {
            future.set(executor.scheduleWithFixedDelay(poll, 0, 1, TimeUnit.SECONDS));
            if (stopped.get()) future.get().cancel(false);
        } catch (RejectedExecutionException e) {
            cleanup.run();
            throw new BusinessException(503, "SERVICE_STOPPING", "服务正在关闭");
        }
        return emitter;
    }

    private void check(UUID run, Actor actor) {
        if (!access.allowed(run, actor)
                || !sources.allowed(run, actor.id())
                || !identities.permissions(actor.id()).contains("get_student_runs_id_events"))
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "运行不存在或不可访问");
    }

    /** 停止调度；容器优雅关闭前不接收新的流。 */
    @PreDestroy
    public void close() {
        executor.shutdownNow();
        active.forEach(
                (emitter, cleanup) -> {
                    cleanup.run();
                    emitter.complete();
                });
    }
}
