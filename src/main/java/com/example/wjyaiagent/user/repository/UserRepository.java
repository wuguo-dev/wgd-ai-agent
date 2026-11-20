package com.example.wjyaiagent.user.repository;

import com.example.wjyaiagent.user.entity.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import org.springframework.jdbc.support.GeneratedKeyHolder;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findByUsername(String username) {
        return find("username", username);
    }

    public Optional<User> findById(long id) {
        return find("id", id);
    }

    public void createStudentIfAbsent(String username, String passwordHash) {
        jdbc.update("INSERT INTO public.community_users(username, password_hash, role, status) "
                + "VALUES (?, ?, 'STUDENT', 'ACTIVE') ON CONFLICT (username) DO NOTHING",
                username, passwordHash);
    }

    public void createAdminIfAbsent(String username, String passwordHash) {
        jdbc.update("INSERT INTO public.community_users(username,password_hash,role,status) VALUES (?,?,'ADMIN','ACTIVE') ON CONFLICT (username) DO NOTHING", username,passwordHash);
    }

    public User createStudent(String username, String passwordHash) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO public.community_users(username, password_hash, role, status) "
                            + "VALUES (?, ?, 'STUDENT', 'ACTIVE')", new String[]{"id"});
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            return statement;
        }, keys);
        return new User(java.util.Objects.requireNonNull(keys.getKey()).longValue(),
                username, passwordHash, "STUDENT", "ACTIVE");
    }

    private Optional<User> find(String column, Object value) {
        return jdbc.query("SELECT id, username, password_hash, role, status FROM public.community_users WHERE "
                        + column + " = ?",
                (rs, row) -> new User(rs.getLong("id"), rs.getString("username"),
                        rs.getString("password_hash"), rs.getString("role"), rs.getString("status")),
                value).stream().findFirst();
    }
}

