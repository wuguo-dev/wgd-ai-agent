package com.example.wjyaiagent.auth.service;

import com.example.wjyaiagent.auth.dto.LoginRequest;
import com.example.wjyaiagent.auth.dto.RegisterRequest;
import com.example.wjyaiagent.auth.dto.RegisterResponse;
import org.springframework.dao.DuplicateKeyException;
import com.example.wjyaiagent.auth.security.LoginThrottle;
import com.example.wjyaiagent.auth.security.StudentPrincipal;
import com.example.wjyaiagent.user.dto.UserInfo;
import com.example.wjyaiagent.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final LoginThrottle throttle;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrf;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, LoginThrottle throttle,
            HttpSessionSecurityContextRepository contexts, HttpSessionCsrfTokenRepository csrf) {
        this.users = users;
        this.encoder = encoder;
        this.throttle = throttle;
        this.contexts = contexts;
        this.csrf = csrf;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    public UserInfo login(LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        if (!throttle.allow(request.getRemoteAddr())) {
            response.setHeader("Retry-After", "600");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "登录尝试过于频繁，请稍后重试");
        }
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码最多72个UTF-8字节");
        }
        var user = users.findByUsername(input.username());
        boolean matches = encoder.matches(input.password(), user.map(value -> value.passwordHash()).orElse(dummyHash));
        if (!matches || user.isEmpty() || !user.get().isActiveAccount()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误，或账号不可用");
        }
        // Discard the anonymous/previous session and CSRF token to prevent session fixation.
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        var session = request.getSession(true);
        session.setMaxInactiveInterval(1800);
        UserInfo info = UserInfo.from(user.get());
        var principal = new StudentPrincipal(info, Instant.now().plusSeconds(1800));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + info.role())));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        csrf.saveToken(null, request, response);
        return info;
    }

    public RegisterResponse register(RegisterRequest input, HttpServletRequest request,
                                     HttpServletResponse response) {
        if (!throttle.allow("register:" + request.getRemoteAddr())) {
            response.setHeader("Retry-After", "600");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "注册尝试过于频繁，请稍后重试");
        }
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码最多72个UTF-8字节");
        }
        if (!input.password().equals(input.confirmPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "两次输入的密码不一致");
        }
        try {
            var user = users.createStudent(input.username(), encoder.encode(input.password()));
            return new RegisterResponse(user.id(), user.username(), user.role());
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
    }

    public UserInfo me() {
        return ((StudentPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).user();
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
        csrf.saveToken(null, request, response);
        // Match the configured context-scoped session cookie. No authentication data is returned to JS.
        var cookie = org.springframework.http.ResponseCookie.from("STUDENT_SESSION", "")
                .path(request.getContextPath().isEmpty() ? "/" : request.getContextPath())
                .httpOnly(true).secure(request.isSecure()).sameSite("Lax").maxAge(0).build();
        response.addHeader("Set-Cookie", cookie.toString());
    }
}

