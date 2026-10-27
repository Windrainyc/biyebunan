package cn.zhixiang.platform.identity.security;

import cn.zhixiang.platform.identity.mapper.IdentityMapper;
import cn.zhixiang.platform.identity.model.Actor;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 不透明令牌认证过滤器；平台由账户决定，不能用客户端角色切换。 */
public class AccessFilter extends OncePerRequestFilter {
    private final IdentityMapper mapper;
    private final TokenDigester digester;

    /**
     * 初始化模块组件，所需端口和配置由构造器注入。
     *
     * @param mapper 身份数据端口
     * @param digester 令牌摘要服务
     */
    public AccessFilter(IdentityMapper mapper, TokenDigester digester) {
        this.mapper = mapper;
        this.digester = digester;
    }

    /**
     * 异步响应分派重新认证，避免SSE完成分派丢失SecurityContext；不放宽访问规则。
     *
     * @return false，继续验证原请求令牌
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && header.length() < 1024) {
            Actor actor = mapper.authenticate(digester.digest("access", header.substring(7)));
            if (actor != null) {
                // URI去除容器contextPath；MockMvc及部分Servlet映射下servletPath可能为空。
                String path = req.getRequestURI().substring(req.getContextPath().length());
                for (String platform : java.util.List.of("admin", "student", "partner")) {
                    if ((path.equals("/api/v1/" + platform)
                                    || path.startsWith("/api/v1/" + platform + "/"))
                            && !actor.platform().equals(platform)) {
                        deny(res, 403, "PLATFORM_FORBIDDEN");
                        return;
                    }
                }
                var authorities =
                        mapper.permissions(actor.id()).stream()
                                .map(SimpleGrantedAuthority::new)
                                .toList();
                SecurityContextHolder.getContext()
                        .setAuthentication(
                                new UsernamePasswordAuthenticationToken(actor, null, authorities));
            }
        }
        chain.doFilter(req, res);
    }

    /**
     * 执行参数所描述的业务变更，异常由统一错误处理器转换。
     *
     * @param response HTTP 响应
     * @param status 状态码
     * @param code 固定错误码
     * @throws IOException 输出失败
     */
    public static void deny(HttpServletResponse response, int status, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json;charset=UTF-8");
        response.getWriter()
                .write(
                        "{\"type\":\"about:blank\",\"title\":\""
                                + code
                                + "\",\"status\":"
                                + status
                                + ",\"code\":\""
                                + code
                                + "\",\"detail\":\"身份或权限不满足要求\",\"traceId\":\""
                                + cn.zhixiang.platform.shared.observability.RequestTrace.errorId()
                                + "\",\"fieldErrors\":[]}");
    }
}
