package cn.zhixiang.platform.identity;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
/** 不透明令牌认证过滤器；平台由账户决定，不能用客户端角色切换。 */
public class AccessFilter extends OncePerRequestFilter {
    private final IdentityMapper mapper; private final TokenDigester digester;
    /** @param mapper 身份数据端口 @param digester 令牌摘要服务 */
    public AccessFilter(IdentityMapper mapper,TokenDigester digester) { this.mapper=mapper;this.digester=digester; }
    @Override
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
        String header=req.getHeader("Authorization");
        if(header!=null && header.startsWith("Bearer ") && header.length()<1024) {
            Actor actor=mapper.authenticate(digester.digest("access",header.substring(7)));
            if(actor!=null) {
                String path=req.getServletPath();
                for(String platform:java.util.List.of("admin","student","partner")) {
                    if(path.startsWith("/api/v1/"+platform+"/") && !actor.platform().equals(platform)) {
                        deny(res,403,"PLATFORM_FORBIDDEN");return;
                    }
                }
                var authorities=mapper.permissions(actor.id()).stream().map(SimpleGrantedAuthority::new).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,authorities));
            }
        }
        chain.doFilter(req,res);
    }
    /** @param response HTTP 响应 @param status 状态码 @param code 固定错误码 @throws IOException 输出失败 */
    public static void deny(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status); response.setContentType("application/problem+json;charset=UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\""+code+"\",\"status\":"+status+",\"code\":\""+code+"\",\"detail\":\"身份或权限不满足要求\",\"traceId\":\""+java.util.UUID.randomUUID()+"\",\"fieldErrors\":[]}");
    }
}
