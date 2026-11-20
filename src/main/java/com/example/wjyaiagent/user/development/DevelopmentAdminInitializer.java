package com.example.wjyaiagent.user.development;

import com.example.wjyaiagent.user.repository.UserRepository;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Explicit development bootstrap. Existing identities are never promoted or overwritten. */
@Component
@Profile("community-admin-dev & !prod & !production")
public class DevelopmentAdminInitializer implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public DevelopmentAdminInitializer(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = System.getenv("DEV_ADMIN_USERNAME");
        String password = System.getenv("DEV_ADMIN_PASSWORD");
        if (username == null
                || !username.matches("[A-Za-z][A-Za-z0-9_]{2,31}")
                || password == null
                || password.isBlank()
                || password.length() < 6
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException(
                    "管理员开发初始化需设置DEV_ADMIN_USERNAME和6字符以上、72个UTF-8字节以内的DEV_ADMIN_PASSWORD");
        users.createAdminIfAbsent(username, encoder.encode(password));
        if (!users.findByUsername(username)
                .filter(user -> "ADMIN".equals(user.role()) && user.isActiveAccount())
                .isPresent()) throw new IllegalStateException("同名账号已存在且非有效管理员，请选择独立开发管理员账号");
    }
}
