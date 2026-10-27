package cn.zhixiang.platform.foundation.service;

import cn.zhixiang.platform.foundation.dto.MajorDtos;
import cn.zhixiang.platform.foundation.dto.SchoolDtos;
import cn.zhixiang.platform.foundation.mapper.EducationDictionaryMapper;
import cn.zhixiang.platform.foundation.mapper.SchoolMapper;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 前台只读教育字典，不允许客户端通过status读取停用记录。 */
@Service
public class EducationDictionaryService {
    private final SchoolMapper schools;
    private final EducationDictionaryMapper majors;

    /**
     * 创建用例。
     *
     * @param schools 院校查询
     * @param majors 专业查询
     */
    public EducationDictionaryService(SchoolMapper schools, EducationDictionaryMapper majors) {
        this.schools = schools;
        this.majors = majors;
    }

    /**
     * 可选院校列表。
     *
     * @param q 已校验分页
     * @return 启用院校；明确查询停用返回空
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<SchoolDtos.View> schools(PageQuery q) {
        if ("DISABLED".equals(q.status()))
            return new PageResult<>(List.of(), q.page(), q.size(), 0);
        var enabled = new PageQuery(q.page(), q.size(), q.q(), "ENABLED", q.sort());
        return new PageResult<>(schools.list(enabled), q.page(), q.size(), schools.count(enabled));
    }

    /**
     * 可选专业列表。
     *
     * @param q 分页
     * @param school 可选院校
     * @return 启用专业
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<MajorDtos.View> majors(PageQuery q, UUID school) {
        if ("DISABLED".equals(q.status()))
            return new PageResult<>(List.of(), q.page(), q.size(), 0);
        return new PageResult<>(
                majors.majors(q, school), q.page(), q.size(), majors.count(q, school));
    }
}
