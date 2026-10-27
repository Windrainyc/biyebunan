package cn.zhixiang.platform.identity.config;

import cn.zhixiang.platform.identity.mapper.IdentityMapper;
import cn.zhixiang.platform.identity.security.AccessFilter;
import cn.zhixiang.platform.identity.security.TokenDigester;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** API 为无状态 Bearer；刷新 Cookie 的 CSRF 将由专用端点进行双提交及 Origin 校验。 */
@Configuration
@EnableMethodSecurity
@Profile("!migrate & !worker & !bootstrap")
public class SecurityConfiguration {
    /** 配置独立平台的无状态安全链。 */
    public SecurityConfiguration() {}

    /**
     * 返回禁用默认用户名密码账户；本工程只使用数据库不透明会话认证。
     *
     * @return 禁用默认用户名密码账户；本工程只使用数据库不透明会话认证
     */
    @Bean
    public org.springframework.security.core.userdetails.UserDetailsService noDefaultUsers() {
        return username -> {
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
                    "仅支持平台认证入口");
        };
    }

    /**
     * 返回安全链。
     *
     * @param http 安全构建器
     * @param mapper 权威会话访问
     * @param digester 密钥化摘要
     * @return 安全链
     * @throws Exception 配置失败
     */
    @Bean
    public SecurityFilterChain apiSecurity(
            HttpSecurity http, IdentityMapper mapper, TokenDigester digester) throws Exception {
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/api/v1/health/live",
                                                "/api/v1/health/ready",
                                                "/api/v1/callbacks/oss")
                                        .permitAll()
                                        .requestMatchers(
                                                "/api/v1/auth/partner/activate",
                                                "/api/v1/auth/admin/activate",
                                                "/api/v1/auth/admin/mfa-enrollments",
                                                "/api/v1/auth/challenges",
                                                "/api/v1/auth/student/register",
                                                "/api/v1/auth/password-reset",
                                                "/api/v1/auth/student/login",
                                                "/api/v1/auth/partner/login",
                                                "/api/v1/auth/admin/login",
                                                "/api/v1/auth/student/refresh",
                                                "/api/v1/auth/partner/refresh",
                                                "/api/v1/auth/admin/refresh",
                                                "/api/v1/auth/student/logout",
                                                "/api/v1/auth/partner/logout",
                                                "/api/v1/auth/admin/logout")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                                (q, r, x) ->
                                                        AccessFilter.deny(
                                                                r, 401, "SESSION_EXPIRED"))
                                        .accessDeniedHandler(
                                                (q, r, x) ->
                                                        AccessFilter.deny(
                                                                r, 403, "PERMISSION_DENIED")))
                .addFilterBefore(
                        new AccessFilter(mapper, digester),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
