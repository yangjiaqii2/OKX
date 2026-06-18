package com.example.quant.controller;

import com.example.quant.auth.AuthLoginRequest;
import com.example.quant.auth.AuthSessionService;
import com.example.quant.auth.AuthUserService;
import com.example.quant.auth.ChangePasswordRequest;
import com.example.quant.auth.CreateUserRequest;
import com.example.quant.auth.ResetPasswordRequest;
import com.example.quant.auth.SetUserEnabledRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/quant/auth")
public class AuthController {
    private final AuthSessionService authSessionService;
    private final AuthUserService authUserService;

    public AuthController(AuthSessionService authSessionService, AuthUserService authUserService) {
        this.authSessionService = authSessionService;
        this.authUserService = authUserService;
    }

    @PostMapping("/login")
    public Object login(@RequestBody AuthLoginRequest request) {
        return authSessionService.login(request);
    }

    @PostMapping("/logout")
    public Object logout(HttpServletRequest request) {
        authSessionService.logout(bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION)));
        return authSessionService.session("");
    }

    @GetMapping("/session")
    public Object session(HttpServletRequest request) {
        return authSessionService.session(bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION)));
    }

    @GetMapping("/users")
    public Object users(HttpServletRequest request) {
        return authUserService.listUsersAs(currentUsername(request));
    }

    @PostMapping("/users")
    public Object createUser(HttpServletRequest request, @RequestBody CreateUserRequest body) {
        return authUserService.createUserAs(currentUsername(request), body);
    }

    @PostMapping("/password/change")
    public Object changeOwnPassword(HttpServletRequest request, @RequestBody ChangePasswordRequest body) {
        return authUserService.changeOwnPassword(currentUsername(request), body);
    }

    @PostMapping("/users/{username}/password")
    public Object resetPassword(
            HttpServletRequest request,
            @PathVariable String username,
            @RequestBody ResetPasswordRequest body
    ) {
        return authUserService.resetPasswordAs(currentUsername(request), username, body);
    }

    @PostMapping("/users/{username}/enabled")
    public Object setEnabled(
            HttpServletRequest request,
            @PathVariable String username,
            @RequestBody SetUserEnabledRequest body
    ) {
        return authUserService.setEnabledAs(currentUsername(request), username, body);
    }

    private String currentUsername(HttpServletRequest request) {
        String token = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        return authSessionService.currentUsername(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录"));
    }

    private static String bearerToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return "";
        }
        return header.substring("Bearer ".length()).trim();
    }
}
