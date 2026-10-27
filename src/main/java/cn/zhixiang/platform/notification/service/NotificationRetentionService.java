package cn.zhixiang.platform.notification.service;

import cn.zhixiang.platform.notification.mapper.NotificationRetentionMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 通知保留期用例；仅按部署显式启用的保留天数执行，不清理账务与审计。 */
@Service
public class NotificationRetentionService {
    private final NotificationRetentionMapper mapper;

    /**
     * 初始化通知保留组件。
     *
     * @param mapper 有界正文清理端口
     */
    public NotificationRetentionService(NotificationRetentionMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 每次短事务最多清理两类记录各100条；0禁用，允许30至3650天。
     *
     * @param days 部署保留天数
     * @return 两类已清理记录总数
     * @throws IllegalArgumentException 非法保留天数
     */
    @Transactional(rollbackFor = Exception.class)
    public int runBatch(int days) {
        if (days == 0) return 0;
        if (days < 30 || days > 3650) throw new IllegalArgumentException("通知保留期必须为0或30至3650天");
        return mapper.redactInbox(days) + mapper.redactTasks(days);
    }
}
