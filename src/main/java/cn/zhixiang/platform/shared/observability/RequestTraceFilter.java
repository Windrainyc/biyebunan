package cn.zhixiang.platform.shared.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** 在认证前建立服务端请求ID，并在异步分派复用；finally清理，防止线程池串号。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final String ATTRIBUTE = RequestTraceFilter.class.getName() + ".id";

    /** 创建请求关联过滤器。 */
    public RequestTraceFilter() {}

    /**
     * @return false，异步分派重新绑定相同请求ID
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * 绑定受控UUID，响应头及错误体共享该值；不记录请求体、令牌或查询参数。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param chain 后续处理
     * @throws ServletException 后续处理失败
     * @throws IOException 响应IO失败
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = (String) request.getAttribute(ATTRIBUTE);
        if (id == null) {
            id = UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, id);
        }
        String previous = MDC.get("requestId");
        MDC.put("requestId", id);
        response.setHeader("X-Request-ID", id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) MDC.remove("requestId");
            else MDC.put("requestId", previous);
        }
    }
}
