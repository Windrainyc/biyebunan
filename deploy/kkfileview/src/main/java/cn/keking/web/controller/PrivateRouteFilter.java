package cn.keking.web.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 关闭上游演示、任意URL预览、上传与静态缓存路由，仅保留内部字节转换。 */
@Component
public class PrivateRouteFilter extends OncePerRequestFilter {
    /** 创建固定路径过滤器。 */
    public PrivateRouteFilter() {}

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equals(request.getMethod())
                || !"/internal/convert".equals(request.getRequestURI())) {
            response.setStatus(404);
            return;
        }
        chain.doFilter(request, response);
    }
}
