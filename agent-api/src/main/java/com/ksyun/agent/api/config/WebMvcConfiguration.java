package com.ksyun.agent.api.config;

import com.ksyun.agent.api.security.SessionAuthenticationInterceptor;
import com.ksyun.agent.application.auth.AuthApplicationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置，注册 SessionAuthenticationInterceptor。
 * <p>
 * 保护路径：/api/agent/**, /api/supervisor/**, /api/admin/**, /api/hitl/**,
 *           /api/auth/me, /api/auth/logout, /api/auth/session
 * 排除路径：/api/auth/login, /api/framework/**, /api/dev/**
 */
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final ObjectProvider<AuthApplicationService> authServiceProvider;

    public WebMvcConfiguration(ObjectProvider<AuthApplicationService> authServiceProvider) {
        this.authServiceProvider = authServiceProvider;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SessionAuthenticationInterceptor(authServiceProvider))
                .addPathPatterns(
                        "/api/agent/**",
                        "/api/supervisor/**",
                        "/api/admin/**",
                        "/api/hitl/**",
                        "/api/context/**",
                        "/api/auth/me",
                        "/api/auth/logout",
                        "/api/auth/session"
                )
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/framework/**",
                        "/api/dev/**"
                );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Session-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
