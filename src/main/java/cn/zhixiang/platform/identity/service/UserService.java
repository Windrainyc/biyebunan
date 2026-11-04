package cn.zhixiang.platform.identity.service;

import cn.zhixiang.platform.identity.dto.AuthDtos;
import cn.zhixiang.platform.identity.dto.UserDtos;
import cn.zhixiang.platform.identity.entity.AuthAccountEntity;
import cn.zhixiang.platform.identity.mapper.AuthMapper;
import cn.zhixiang.platform.identity.mapper.IdentityMapper;
import cn.zhixiang.platform.identity.mapper.RoleMapper;
import cn.zhixiang.platform.identity.mapper.UserMapper;
import cn.zhixiang.platform.identity.security.Passwords;
import cn.zhixiang.platform.identity.security.TokenDigester;
import cn.zhixiang.platform.shared.dto.PageQuery;
import cn.zhixiang.platform.shared.dto.PageResult;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 用户管理领域规则；不允许通过普通启停绕过邀请激活或移除最后管理员。 */
@Service
public class UserService {
    private final UserMapper users;
    private final AuthMapper accounts;
    private final RoleMapper roles;
    private final IdentityMapper identity;
    private final TokenDigester digester;
    private final Passwords passwords;
    private final WriteMapper writes;

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param users 用户数据
     * @param accounts 账户锁
     * @param roles 角色目录
     * @param identity 当前权限
     * @param digester 手机摘要
     * @param passwords 密码摘要
     * @param writes 审计
     */
    public UserService(
            UserMapper users,
            AuthMapper accounts,
            RoleMapper roles,
            IdentityMapper identity,
            TokenDigester digester,
            Passwords passwords,
            WriteMapper writes) {
        this.users = users;
        this.accounts = accounts;
        this.roles = roles;
        this.identity = identity;
        this.digester = digester;
        this.passwords = passwords;
        this.writes = writes;
    }

    /**
     * 返回脱敏分页。
     *
     * @param q 分页
     * @param platform 平台筛选
     * @param roleId 角色筛选
     * @return 脱敏分页
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<AuthDtos.User> list(PageQuery q, String platform, UUID roleId) {
        if (platform != null && !Set.of("admin", "student", "partner").contains(platform))
            throw new BusinessException(400, "INVALID_FILTER", "平台筛选无效");
        return new PageResult<>(
                users.list(q, platform, roleId).stream().map(this::view).toList(),
                q.page(),
                q.size(),
                users.count(q, platform, roleId));
    }

    /**
     * 返回脱敏详情。
     *
     * @param id 账户
     * @return 脱敏详情
     */
    public AuthDtos.User get(UUID id) {
        var a = users.get(id);
        if (a == null) throw missing();
        return view(a);
    }

    /**
     * 返回禁用且待激活的账户。
     *
     * @param w 新账户
     * @param actor 操作者
     * @return 禁用且待激活的账户
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthDtos.User create(UserDtos.Create w, UUID actor) {
        roles.lockIam();
        if (w.platform().equals("student"))
            throw new BusinessException(422, "INVALID_TRANSITION", "学生需通过手机号验证自助注册");
        validateRoles(w.roleIds(), w.platform(), actor);
        UUID id = UUID.randomUUID();
        users.insert(
                id,
                w,
                digester.digest("phone", w.phone()),
                w.phone().substring(0, 3) + "****" + w.phone().substring(7),
                passwords.encode(UUID.randomUUID().toString() + UUID.randomUUID()));
        setRoles(id, w.roleIds());
        audit(id, actor, "CREATED");
        return get(id);
    }

    /**
     * 返回更新后的脱敏账户。
     *
     * @param id 账户
     * @param version 期望版本
     * @param w 新资料
     * @param actor 操作者
     * @return 更新后的脱敏账户
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthDtos.User update(UUID id, long version, UserDtos.Write w, UUID actor) {
        roles.lockIam();
        var old = accounts.lockAccount(id);
        if (old == null) throw missing();
        if (old.version() != version) throw conflict();
        assertCanManage(id, actor);
        validateRoles(w.roleIds(), old.platform(), actor);
        if ("ENABLED".equals(w.status()) && users.activated(id) == 0)
            throw new BusinessException(422, "ACTIVATION_REQUIRED", "账户需完成邀请激活，不能直接启用");
        if (w.majorId() != null && w.schoolId() == null
                || w.schoolId() != null && users.validEducation(w.schoolId(), w.majorId()) != 1)
            throw new BusinessException(422, "INVALID_REFERENCE", "院校专业必须启用且相互匹配");
        UUID superRole = UUID.fromString("00000000-0000-4000-8000-000000000001");
        if (identity.superAdmin(id) > 0
                && (!w.status().equals("ENABLED") || !w.roleIds().contains(superRole))
                && users.activeSupers() <= 1)
            throw new BusinessException(409, "LAST_ADMIN_REQUIRED", "不能停用或移除最后一个超级管理员");
        if (users.update(id, version, w) != 1) throw conflict();
        setRoles(id, w.roleIds());
        writes.auditReason(UUID.randomUUID(), actor, "UPDATED", "user", id, w.reason());
        writes.event(UUID.randomUUID(), id, "user.UPDATED", "{\"id\":\"" + id + "\"}");
        return get(id);
    }

    /**
     * 返回启停结果。
     *
     * @param id 账户
     * @param version 版本
     * @param state 状态
     * @param actor 操作者
     * @return 启停结果
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthDtos.User status(UUID id, long version, String state, UUID actor) {
        roles.lockIam();
        var old = accounts.lockAccount(id);
        if (old == null) throw missing();
        if (old.version() != version) throw conflict();
        if (old.status().equals(state)) return view(old);
        return update(
                id,
                version,
                new UserDtos.Write(
                        old.displayName(),
                        old.schoolId(),
                        old.majorId(),
                        accounts.roles(id),
                        state,
                        "账户启停"),
                actor);
    }

    /**
     * 验证操作者可管理目标当前角色，防止低权限管理员重置高权限账户。
     *
     * @param id 目标账户
     * @param actor 操作者
     */
    public void assertCanManage(UUID id, UUID actor) {
        if (users.get(id) == null) throw missing();
        if (identity.superAdmin(actor) > 0) return;
        var owned = new HashSet<>(identity.permissions(actor));
        for (UUID role : accounts.roles(id)) {
            var r = roles.get(role);
            if (r.code().equals("SUPER_ADMIN") || !owned.containsAll(roles.codes(role)))
                throw new BusinessException(403, "PERMISSION_DENIED", "不能管理权限高于自身的账户");
        }
    }

    private void validateRoles(List<UUID> ids, String platform, UUID actor) {
        if (ids.isEmpty() || ids.size() != new HashSet<>(ids).size())
            throw new BusinessException(400, "VALIDATION_ERROR", "角色不能为空或重复");
        boolean superAdmin = identity.superAdmin(actor) > 0;
        var owned = new HashSet<>(identity.permissions(actor));
        for (UUID id : ids) {
            var r = roles.get(id);
            if (r == null || !r.platform().equals(platform) || !r.status().equals("ENABLED"))
                throw new BusinessException(422, "INVALID_REFERENCE", "角色必须启用且与账户平台相同");
            if (!superAdmin
                    && (r.code().equals("SUPER_ADMIN") || !owned.containsAll(roles.codes(id))))
                throw new BusinessException(403, "PERMISSION_DENIED", "不能分配超出自身权限的角色");
        }
    }

    private void setRoles(UUID id, List<UUID> ids) {
        users.clearRoles(id);
        ids.forEach(r -> users.addRole(id, r));
    }

    private AuthDtos.User view(AuthAccountEntity a) {
        return new AuthDtos.User(
                a.id(),
                a.version(),
                a.createdAt(),
                a.updatedAt(),
                a.displayName(),
                a.phoneMasked(),
                a.platform(),
                a.status(),
                a.schoolId(),
                a.majorId(),
                accounts.roles(a.id()),
                a.avatarIndex(),
                a.avatarAssetId());
    }

    private void audit(UUID id, UUID actor, String action) {
        writes.audit(UUID.randomUUID(), actor, action, "user", id);
        writes.event(UUID.randomUUID(), id, "user." + action, "{\"id\":\"" + id + "\"}");
    }

    private static BusinessException missing() {
        return new BusinessException(404, "RESOURCE_NOT_FOUND", "账户不存在");
    }

    private static BusinessException conflict() {
        return new BusinessException(412, "VERSION_CONFLICT", "账户版本已改变");
    }
}
