package com.example.wjyaiagent.auth.security;

import com.example.wjyaiagent.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Instant;

/** Only installed in the student security chain, never registered as a servlet filter. */
public class StudentSessionFilter extends OncePerRequestFilter {
    private final UserRepository users;
    private final ObjectMapper mapper;

    public StudentSessionFilter(UserRepository users, ObjectMapper mapper) {
        this.users = users;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof StudentPrincipal principal) {
            boolean valid;
            try {
                valid = principal.expiresAt().isAfter(Instant.now())
                        && users.findById(principal.user().id()).filter(user -> user.isActiveAccount() && user.role().equals(principal.user().role())
                        && user.username().equals(principal.user().username())).isPresent();
            } catch (DataAccessException error) {
                AuthErrorWriter.write(response, mapper, 503, "AUTH_UNAVAILABLE", "登录服务暂时不可用");
                return;
            }
            if (!valid) {
                if (request.getSession(false) != null) request.getSession(false).invalidate();
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}

