package cn.zhixiang.platform.identity;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
/** API 为无状态 Bearer；刷新 Cookie 的 CSRF 将由专用端点进行双提交及 Origin 校验。 */
@Configuration
@EnableMethodSecurity
@Profile("!migrate")
public class SecurityConfiguration {
    /** @param http 安全构建器 @param mapper 权威会话访问 @param digester 密钥化摘要 @return 安全链 @throws Exception 配置失败 */
    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http,IdentityMapper mapper,TokenDigester digester) throws Exception {
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/actuator/health/**").permitAll().anyRequest().authenticated())
          .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->AccessFilter.deny(r,401,"SESSION_EXPIRED"))
            .accessDeniedHandler((q,r,x)->AccessFilter.deny(r,403,"PERMISSION_DENIED")))
          .addFilterBefore(new AccessFilter(mapper,digester),UsernamePasswordAuthenticationFilter.class).build();
    }
}
