package com.example.wjyaiagent.user.development;

import com.example.wjyaiagent.user.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

/** Explicit development profile only. No default account, password or overwrites. */
@Component
@Profile("student-dev & !prod & !production")
public class DevelopmentStudentInitializer implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public DevelopmentStudentInitializer(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = System.getenv("DEV_STUDENT_USERNAME");
        String password = System.getenv("DEV_STUDENT_PASSWORD");
        if (username == null || username.isBlank() || username.length() > 64
                || password == null || password.isBlank() || password.length() < 6
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("开发账号需配置DEV_STUDENT_USERNAME及至少6个字符且最多72个UTF-8字节的DEV_STUDENT_PASSWORD");
        }
        users.createStudentIfAbsent(username.trim(), encoder.encode(password));
    }
}
