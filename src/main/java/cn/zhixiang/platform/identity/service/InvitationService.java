package cn.zhixiang.platform.identity.service;

import cn.zhixiang.platform.identity.dto.ActivationDtos;
import cn.zhixiang.platform.identity.dto.AuthDtos;
import cn.zhixiang.platform.identity.dto.UserDtos;
import cn.zhixiang.platform.identity.entity.InvitationEnrollmentEntity;
import cn.zhixiang.platform.identity.entity.InvitationEntity;
import cn.zhixiang.platform.identity.mapper.AuthMapper;
import cn.zhixiang.platform.identity.mapper.InvitationMapper;
import cn.zhixiang.platform.identity.mapper.RoleMapper;
import cn.zhixiang.platform.identity.mapper.UserMapper;
import cn.zhixiang.platform.identity.mapper.VerificationMapper;
import cn.zhixiang.platform.identity.security.Passwords;
import cn.zhixiang.platform.identity.security.TokenDigester;
import cn.zhixiang.platform.identity.security.TotpVerifier;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/** 邀请开通领域服务：账户保持DISABLED直至一次性证明全部通过。外部短信不在此事务发送。 */
@Service
public class InvitationService {
    private final InvitationMapper invitations;
    private final AuthMapper accounts;
    private final UserService users;
    private final UserMapper userRows;
    private final RoleMapper roles;
    private final TokenDigester digester;
    private final Passwords passwords;
    private final SecretVault vault;
    private final TotpVerifier totp;
    private final VerificationMapper challenges;
    private final VerificationService verification;
    private final AuthService auth;
    private final TransactionTemplate tx;
    private final WriteMapper writes;
    private final String terms, adminOrigin, partnerOrigin;
    private final SecureRandom random = new SecureRandom();

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param invitations 邀请端口
     * @param accounts 账户锁
     * @param users 用户规则
     * @param userRows 用户状态
     * @param roles IAM互斥锁
     * @param digester 令牌摘要
     * @param passwords 密码摘要
     * @param vault 加密种子
     * @param totp 动态码校验
     * @param challenges 手机证明
     * @param verification 挑战验证
     * @param auth 会话签发
     * @param tx 事务模板
     * @param writes 审计
     * @param terms 服务条款版本
     * @param adminOrigin 管理端来源
     * @param partnerOrigin 中介端来源
     */
    public InvitationService(
            InvitationMapper invitations,
            AuthMapper accounts,
            UserService users,
            UserMapper userRows,
            RoleMapper roles,
            TokenDigester digester,
            Passwords passwords,
            SecretVault vault,
            TotpVerifier totp,
            VerificationMapper challenges,
            VerificationService verification,
            AuthService auth,
            TransactionTemplate tx,
            WriteMapper writes,
            @Value("${ZX_TERMS_VERSION:2026-10-01}") String terms,
            @Value("${ZX_ADMIN_ORIGIN:https://admin.invalid}") String adminOrigin,
            @Value("${ZX_PARTNER_ORIGIN:https://partner.invalid}") String partnerOrigin) {
        this.invitations = invitations;
        this.accounts = accounts;
        this.users = users;
        this.userRows = userRows;
        this.roles = roles;
        this.digester = digester;
        this.passwords = passwords;
        this.vault = vault;
        this.totp = totp;
        this.challenges = challenges;
        this.verification = verification;
        this.auth = auth;
        this.tx = tx;
        this.writes = writes;
        this.terms = terms;
        this.adminOrigin = adminOrigin;
        this.partnerOrigin = partnerOrigin;
    }

    /**
     * 返回仅供安全传递的一次性邀请链接。
     *
     * @param input 中介基本资料
     * @param actor 当前管理员
     * @return 仅供安全传递的一次性邀请链接
     */
    @Transactional(rollbackFor = Exception.class)
    public UserDtos.Invitation partner(UserDtos.Invite input, UUID actor) {
        var user =
                users.create(
                        new UserDtos.Create(
                                input.phone(),
                                input.displayName(),
                                "partner",
                                List.of(UUID.fromString("00000000-0000-4000-8000-000000000003"))),
                        actor);
        return invite(user.id(), input.expiresInHours(), actor);
    }

    /**
     * 返回新邀请；同账户旧邀请立即失效。
     *
     * @param id 待激活账户
     * @param hours 有效小时数
     * @param actor 管理员
     * @return 新邀请；同账户旧邀请立即失效
     */
    @Transactional(rollbackFor = Exception.class)
    public UserDtos.Invitation invite(UUID id, int hours, UUID actor) {
        roles.lockIam();
        var account = accounts.lockAccount(id);
        if (account == null) throw invalid();
        users.assertCanManage(id, actor);
        if (hours < 1
                || hours > 72
                || account.platform().equals("student")
                || userRows.activated(id) > 0
                || !account.status().equals("DISABLED"))
            throw new BusinessException(422, "INVALID_TRANSITION", "仅可邀请未激活的管理员或中介");
        byte[] token = new byte[32];
        random.nextBytes(token);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        UUID invitation = UUID.randomUUID();
        var expiry = OffsetDateTime.now().plusHours(hours);
        invitations.invalidate(id);
        invitations.insert(invitation, id, digester.digest("invitation", raw), expiry);
        writes.audit(UUID.randomUUID(), actor, "INVITED", "user", id);
        // 使用fragment防止浏览器将令牌放入HTTP请求路径、代理日志和Referer。
        return new UserDtos.Invitation(
                invitation,
                expiry,
                (account.platform().equals("admin") ? adminOrigin : partnerOrigin)
                        + "/activate#token="
                        + raw);
    }

    /**
     * 返回MFA二维码URI；相同有效邀请复用种子，防止并发登记覆盖。
     *
     * @param input 管理员邀请凭证
     * @param ip 直连来源
     * @return MFA二维码URI；相同有效邀请复用种子，防止并发登记覆盖
     */
    public ActivationDtos.Enrollment enroll(ActivationDtos.Enroll input, String ip) {
        limit(input.invitationToken(), ip);
        return tx.execute(
                s -> {
                    var invite = locked(input.invitationToken(), "admin");
                    var existing = invitations.enrollment(invite.id());
                    if (existing != null) {
                        if (existing.consumedAt() != null
                                || !existing.expiresAt().isAfter(OffsetDateTime.now()))
                            throw invalid();
                        return enrollmentView(existing);
                    }
                    byte[] bytes = new byte[20];
                    random.nextBytes(bytes);
                    StringBuilder seed = new StringBuilder();
                    int buffer = 0, bits = 0;
                    for (byte b : bytes) {
                        buffer = (buffer << 8) | (b & 255);
                        bits += 8;
                        while (bits >= 5) {
                            bits -= 5;
                            seed.append(
                                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
                                            .charAt((buffer >> bits) & 31));
                        }
                    }
                    UUID secret = vault.put(seed.toString()), id = UUID.randomUUID();
                    var expiry = OffsetDateTime.now().plusMinutes(15);
                    if (expiry.isAfter(invite.expiresAt())) expiry = invite.expiresAt();
                    invitations.enroll(id, invite.id(), secret, expiry);
                    return enrollmentView(invitations.enrollment(invite.id()));
                });
    }

    /**
     * 返回激活后的中介会话；验证码失败次数单独提交。
     *
     * @param input 手机证明和中介邀请
     * @param ip 来源
     * @return 激活后的中介会话；验证码失败次数单独提交
     */
    public AuthDtos.Issued activatePartner(ActivationDtos.Partner input, String ip) {
        limit(input.invitationToken(), ip);
        terms(input.termsVersion());
        String hash = passwords.encode(input.password());
        var result =
                tx.execute(
                        s -> {
                            var invite = locked(input.invitationToken(), "partner");
                            var account = accounts.lockAccount(invite.userId());
                            String phone = digester.digest("phone", input.phone());
                            var phoneAccount = accounts.find("partner", phone);
                            if (phoneAccount == null || !phoneAccount.id().equals(account.id()))
                                throw invalid();
                            if (!verification.valid(
                                    challenges.lock(input.challengeId()),
                                    "ACTIVATE",
                                    "partner",
                                    phone,
                                    input.code())) return null;
                            challenges.consume(input.challengeId());
                            activate(invite, hash, input.displayName(), null, null);
                            return auth.issueVerified(accounts.lockAccount(account.id()));
                        });
        if (result == null) throw invalid();
        return result;
    }

    /**
     * 返回激活后的管理员会话，消费当前MFA时间步防止重放。
     *
     * @param input 管理员邀请及MFA动态码
     * @param ip 来源
     * @return 激活后的管理员会话，消费当前MFA时间步防止重放
     */
    public AuthDtos.Issued activateAdmin(ActivationDtos.Admin input, String ip) {
        limit(input.invitationToken(), ip);
        String hash = passwords.encode(input.password());
        return tx.execute(
                s -> {
                    var invite = locked(input.invitationToken(), "admin");
                    var enrollment = invitations.enrollment(invite.id());
                    if (enrollment == null
                            || !enrollment.id().equals(input.mfaEnrollmentId())
                            || enrollment.consumedAt() != null
                            || !enrollment.expiresAt().isAfter(OffsetDateTime.now()))
                        throw invalid();
                    long step = totp.verify(enrollment.secretId().toString(), input.mfaCode());
                    activate(
                            invite,
                            hash,
                            accounts.lockAccount(invite.userId()).displayName(),
                            enrollment.secretId().toString(),
                            step);
                    invitations.consumeEnrollment(enrollment.id());
                    return auth.issueVerified(accounts.lockAccount(invite.userId()));
                });
    }

    private InvitationEntity locked(String raw, String platform) {
        var found = invitations.find(digester.digest("invitation", raw));
        if (found == null) throw invalid();
        var account = accounts.lockAccount(found.userId());
        var row = invitations.lock(found.id());
        if (account == null
                || !account.platform().equals(platform)
                || !account.status().equals("DISABLED")
                || userRows.activated(account.id()) > 0
                || row.consumedAt() != null
                || !row.expiresAt().isAfter(OffsetDateTime.now())) throw invalid();
        return row;
    }

    private void activate(
            InvitationEntity row, String hash, String name, String secret, Long step) {
        if (invitations.activate(row.userId(), hash, name.trim(), terms, secret, step) != 1)
            throw invalid();
        invitations.consume(row.id());
        writes.audit(UUID.randomUUID(), row.userId(), "ACTIVATED", "user", row.userId());
        writes.event(
                UUID.randomUUID(),
                row.userId(),
                "user.ACTIVATED",
                "{\"id\":\"" + row.userId() + "\"}");
    }

    private ActivationDtos.Enrollment enrollmentView(InvitationEnrollmentEntity e) {
        return new ActivationDtos.Enrollment(
                e.id(),
                "otpauth://totp/Zhixiang:"
                        + e.id()
                        + "?secret="
                        + vault.get(e.secretId())
                        + "&issuer=Zhixiang&algorithm=SHA1&digits=6&period=30",
                e.expiresAt());
    }

    private void limit(String token, String ip) {
        if (accounts.attempt(digester.digest("activation-ip", ip)) > 40
                || accounts.attempt(digester.digest("activation-token", token)) > 10)
            throw new BusinessException(429, "RATE_LIMITED", "激活尝试过多，请十分钟后重试");
    }

    private void terms(String version) {
        if (!terms.equals(version))
            throw new BusinessException(422, "TERMS_VERSION_REQUIRED", "请同意当前服务条款");
    }

    private static BusinessException invalid() {
        return new BusinessException(401, "VERIFICATION_INVALID", "邀请或验证材料无效、已使用或已过期");
    }
}
