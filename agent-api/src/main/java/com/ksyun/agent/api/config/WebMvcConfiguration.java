package com.ksyun.agent.api.config;

import com.ksyun.agent.api.security.SessionAuthenticationInterceptor;
import com.ksyun.agent.application.auth.AuthApplicationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Web MVC 配置，注册 SessionAuthenticationInterceptor。
 * <p>
 * 保护路径：/api/agent/**, /api/supervisor/**, /api/admin/**, /api/hitl/**,
 *           /api/auth/me, /api/auth/logout, /api/auth/session
 * 排除路径：/api/auth/login, /api/auth/register, /api/framework/**, /api/dev/**
 */
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final ObjectProvider<AuthApplicationService> authServiceProvider;
    private final String[] allowedOriginPatterns;

    public WebMvcConfiguration(
            ObjectProvider<AuthApplicationService> authServiceProvider,
            @Value("${agent.web.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
            String configuredOriginPatterns
    ) {
        this.authServiceProvider = authServiceProvider;
        this.allowedOriginPatterns = Arrays.stream(configuredOriginPatterns.split(","))
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .toArray(String[]::new);
        if (allowedOriginPatterns.length == 0) {
            throw new IllegalArgumentException("agent.web.allowed-origin-patterns must not be empty");
        }
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
                        "/api/auth/session",
                        "/api/conversations/**"
                )
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/framework/**",
                        "/api/dev/**"
                );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Session-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
