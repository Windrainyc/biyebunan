package cn.zhixiang.platform.chat.service;

import cn.zhixiang.platform.chat.entity.RunLeaseLeaseEntity;
import cn.zhixiang.platform.chat.mapper.RunLeaseMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 运行领取和调用起点短事务。调用外部供应商的代码必须在本服务方法返回之后执行。 */
@Service
public class RunLeaseService {
    private final RunLeaseMapper mapper;

    /**
     * 创建租约服务。
     *
     * @param mapper 原子CAS端口
     */
    public RunLeaseService(RunLeaseMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 领取未调用运行。
     *
     * @param owner 本进程标识
     * @return 运行或null
     */
    @Transactional(rollbackFor = Exception.class)
    public RunLeaseLeaseEntity claim(UUID owner) {
        return mapper.claim(owner);
    }

    /**
     * 提交外部调用意图；false表示失去租约，调用者必须停止且不得请求供应商。
     *
     * @param run 运行
     * @param owner 持有者
     * @param generation 领取代次
     * @return 是否允许发起一次调用
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean start(UUID run, UUID owner, long generation) {
        return mapper.start(run, owner, generation) == 1;
    }
}
