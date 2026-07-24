package com.ksyun.agent.api.security;

import com.ksyun.agent.application.auth.AuthApplicationService;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.security.UserSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Session 认证拦截器。
 * <p>
 * 从 X-Session-Id Header 提取 sessionId，验证后放入 HttpServletRequest 属性。
 * Controller 通过 @RequestAttribute 读取，不得放入 ThreadLocal。
 * OPTIONS 预检请求按照现有 CORS 策略放行。
 * 不得拦截静态资源。
 * 无效 Session 请求不得进入 Controller 方法（logout 除外，保证幂等）。
 */
public class SessionAuthenticationInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SessionAuthenticationInterceptor.class);
    private static final String SESSION_HEADER = "X-Session-Id";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<String> SKIP_AUTH_PATHS = Set.of(
            "/api/auth/login"
    );

    private static final Set<String> SKIP_AUTH_PREFIXES = Set.of(
            "/api/framework/",
            "/api/dev/"
    );

    /**
     * 登出路径：session 不存在时仍放行到 controller，保证登出幂等。
     */
    private static final String LOGOUT_PATH = "/api/auth/logout";

    private final ObjectProvider<AuthApplicationService> authServiceProvider;

    public SessionAuthenticationInterceptor(ObjectProvider<AuthApplicationService> authServiceProvider) {
        this.authServiceProvider = authServiceProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // OPTIONS 预检请求放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 跳过不需要认证的路径
        if (shouldSkipAuth(request.getRequestURI())) {
            return true;
        }

        AuthApplicationService authService = authServiceProvider.getIfAvailable();
        if (authService == null) {
            writeErrorResponse(response, HttpStatus.SERVICE_UNAVAILABLE,
                    AgentErrorCode.AUTHENTICATION_FAILED.name(),
                    "Authentication service is not available");
            return false;
        }

        String sessionId = request.getHeader(SESSION_HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            // 登出路径缺少 session header 时放行到 controller（幂等）
            if (isLogoutPath(request.getRequestURI())) {
                return true;
            }
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED,
                    AgentErrorCode.SESSION_NOT_FOUND.name(),
                    "Missing or blank " + SESSION_HEADER + " header");
            return false;
        }

        try {
            UserSession session = authService.getSession(sessionId.trim());
            // 将 UserSession 放入 HttpServletRequest 属性
            request.setAttribute(AuthenticatedSessionAttributes.SESSION, session);
            return true;
        } catch (AgentFrameworkException e) {
            // 登出路径 session 无效时放行到 controller（幂等）
            if (isLogoutPath(request.getRequestURI())) {
                return true;
            }
            HttpStatus httpStatus = mapAuthErrorToHttpStatus(e.getErrorCode());
            writeErrorResponse(response, httpStatus, e.getErrorCode().name(), e.getMessage());
            return false;
        }
    }

    private boolean shouldSkipAuth(String requestUri) {
        if (requestUri == null) {
            return false;
        }
        for (String path : SKIP_AUTH_PATHS) {
            if (requestUri.equals(path)) {
                return true;
            }
        }
        for (String prefix : SKIP_AUTH_PREFIXES) {
            if (requestUri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLogoutPath(String requestUri) {
        return LOGOUT_PATH.equals(requestUri);
    }

    private void writeErrorResponse(HttpServletResponse response, HttpStatus status,
                                     String errorCode, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, String> errorBody = Map.of(
                "errorCode", errorCode,
                "message", message != null ? message : "Authentication failed"
        );
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(errorBody));
    }

    private HttpStatus mapAuthErrorToHttpStatus(AgentErrorCode errorCode) {
        return switch (errorCode) {
            case SESSION_NOT_FOUND, SESSION_INVALID, SESSION_EXPIRED, AUTHENTICATION_FAILED ->
                    HttpStatus.UNAUTHORIZED;
            case INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
            case USER_DISABLED -> HttpStatus.FORBIDDEN;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
