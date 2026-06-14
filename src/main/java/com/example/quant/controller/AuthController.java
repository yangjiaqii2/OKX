package com.example.quant.controller;

import com.example.quant.auth.AuthLoginRequest;
import com.example.quant.auth.AuthSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/auth")
public class AuthController {
    private final AuthSessionService authSessionService;

    public AuthController(AuthSessionService authSessionService) {
        this.authSessionService = authSessionService;
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

    private static String bearerToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return "";
        }
        return header.substring("Bearer ".length()).trim();
    }
}
