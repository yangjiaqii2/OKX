package com.example.quant.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final AuthSessionService authSessionService;

    public AuthInterceptor(AuthSessionService authSessionService) {
        this.authSessionService = authSessionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublicAuthEndpoint(request)) {
            AuthUserContext.clear();
            return true;
        }
        String token = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        Optional<String> username;
        if (handler instanceof HandlerMethod method && method.hasMethodAnnotation(AdminOnly.class)) {
            AuthSessionResponse session = authSessionService.session(token);
            if (session.authenticated() && !AuthRole.ADMIN.name().equals(session.role())) {
                AuthUserContext.clear();
                response.setStatus(HttpStatus.FORBIDDEN.value());
                return false;
            }
            username = session.authenticated() ? Optional.of(session.username()) : Optional.empty();
        } else {
            username = authSessionService.currentUsername(token);
        }
        if (username.isPresent()) {
            AuthUserContext.setCurrentUsername(username.orElseThrow());
            return true;
        }
        AuthUserContext.clear();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthUserContext.clear();
    }

    private static boolean isPublicAuthEndpoint(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if ("/api/quant/auth/login".equals(uri)) {
            return true;
        }
        return "GET".equalsIgnoreCase(request.getMethod()) && "/api/quant/auth/session".equals(uri);
    }

    private static String bearerToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return "";
        }
        return header.substring("Bearer ".length()).trim();
    }
}
