package cn.zhixiang.platform.chat.service;

import cn.zhixiang.platform.chat.dto.AvailableModelResponse;
import cn.zhixiang.platform.chat.mapper.AvailableModelMapper;
import cn.zhixiang.platform.chat.port.ModelGateway;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 学生模型目录；配置可用性过滤必须先于分页，避免总数包含不可调用模型。 */
@Service
public class AvailableModelService {
    private final AvailableModelMapper mapper;
    private final ModelGateway gateway;

    /**
     * 创建目录用例。
     *
     * @param mapper 候选配置查询
     * @param gateway 无网络配置预检
     */
    public AvailableModelService(AvailableModelMapper mapper, ModelGateway gateway) {
        this.mapper = mapper;
        this.gateway = gateway;
    }

    /**
     * 返回当前配置允许调用的模型，不保证供应商在线或学生额度足够。
     *
     * @param query 已验证分页过滤
     * @param actor 服务端学生身份
     * @return 不含密钥和内部端点的分页目录
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<AvailableModelResponse> list(PageQuery query, Actor actor) {
        if (!"student".equals(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "仅学生可查看模型目录");
        if ("DISABLED".equals(query.status()))
            return new PageResult<>(List.of(), query.page(), query.size(), 0);
        List<AvailableModelResponse> available = new ArrayList<>();
        for (var row : mapper.candidates(query)) {
            try {
                gateway.check(row.endpoint());
            } catch (ModelGateway.Failure unavailable) {
                continue;
            }
            available.add(
                    new AvailableModelResponse(
                            row.id(), row.name(), Math.min(row.maxOutputTokens(), 8192)));
        }
        int start = Math.min(query.offset(), available.size());
        int end = (int) Math.min((long) start + query.size(), available.size());
        return new PageResult<>(
                List.copyOf(available.subList(start, end)),
                query.page(),
                query.size(),
                available.size());
    }
}
