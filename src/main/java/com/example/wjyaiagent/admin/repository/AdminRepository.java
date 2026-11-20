package com.example.wjyaiagent.admin.repository;

import com.example.wjyaiagent.admin.dto.AdminDtos.*;
import com.example.wjyaiagent.community.dto.CommunityDtos.Author;
import com.example.wjyaiagent.community.dto.CommunityDtos.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class AdminRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final String USER_COLUMNS =
            "SELECT id,username,role,status,created_at,updated_at FROM public.community_users";
    private static final RowMapper<User> USER =
            (rs, n) ->
                    new User(
                            rs.getLong("id"),
                            rs.getString("username"),
                            rs.getString("role"),
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("updated_at").toInstant());

    public AdminRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<User> user(long id, boolean lock) {
        return jdbc
                .query(USER_COLUMNS + " WHERE id=?" + (lock ? " FOR UPDATE" : ""), USER, id)
                .stream()
                .findFirst();
    }

    public Page<User> users(String where, List<Object> args, int page, int size, boolean oldest) {
        long count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.community_users WHERE " + where,
                        Long.class,
                        args.toArray());
        var queryArgs = new ArrayList<>(args);
        queryArgs.add(size);
        queryArgs.add((long) (page - 1) * size);
        return new Page<>(
                jdbc.query(
                        USER_COLUMNS
                                + " WHERE "
                                + where
                                + (oldest
                                        ? " ORDER BY created_at ASC,id ASC"
                                        : " ORDER BY created_at DESC,id DESC")
                                + " LIMIT ? OFFSET ?",
                        USER,
                        queryArgs.toArray()),
                page,
                size,
                count);
    }

    public int userStatus(long id, String before, String after) {
        return jdbc.update(
                "UPDATE public.community_users SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?"
                    + " AND role='STUDENT' AND status=?",
                after,
                id,
                before);
    }

    public void log(
            long operator,
            String action,
            String type,
            long target,
            String reason,
            Object before,
            Object after) {
        jdbc.update(
                "INSERT INTO"
                    + " public.admin_operation_log(operator_id,action,target_type,target_id,reason,before_state,after_state)"
                    + " VALUES (?,?,?,?,?,?,?)",
                operator,
                action,
                type,
                target,
                reason,
                json(before),
                json(after));
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法记录操作状态", error);
        }
    }

    public Page<OperationLog> logs(String where, List<Object> args, int page, int size) {
        String from =
                " FROM public.admin_operation_log l JOIN public.community_users u ON"
                    + " u.id=l.operator_id WHERE ";
        long count =
                jdbc.queryForObject("SELECT count(*)" + from + where, Long.class, args.toArray());
        var queryArgs = new ArrayList<>(args);
        queryArgs.add(size);
        queryArgs.add((long) (page - 1) * size);
        var items =
                jdbc.query(
                        "SELECT l.*,u.username"
                                + from
                                + where
                                + " ORDER BY l.created_at DESC,l.id DESC LIMIT ? OFFSET ?",
                        (rs, n) ->
                                new OperationLog(
                                        rs.getLong("id"),
                                        new Author(
                                                rs.getLong("operator_id"),
                                                rs.getString("username")),
                                        rs.getString("action"),
                                        rs.getString("target_type"),
                                        rs.getLong("target_id"),
                                        rs.getString("reason"),
                                        rs.getString("before_state"),
                                        rs.getString("after_state"),
                                        rs.getTimestamp("created_at").toInstant()),
                        queryArgs.toArray());
        return new Page<>(items, page, size, count);
    }

    public Stats stats() {
        return jdbc.queryForObject(
                """
SELECT
(SELECT count(*) FROM public.community_users WHERE role='STUDENT') student_total,
(SELECT count(*) FROM public.community_users WHERE role='STUDENT' AND status='ACTIVE') active_students,
(SELECT count(*) FROM public.community_users WHERE role='STUDENT' AND status='DISABLED') disabled_students,
(SELECT count(*) FROM public.community_question) question_total,
(SELECT count(*) FROM public.community_answer) answer_total,
(SELECT count(*) FROM public.community_question WHERE status='PENDING' AND deleted_at IS NULL) pending_questions,
(SELECT count(*) FROM public.community_answer a JOIN public.community_question q ON q.id=a.question_id WHERE a.status='PENDING' AND a.deleted_at IS NULL AND q.deleted_at IS NULL AND q.status='APPROVED') pending_answers,
(SELECT count(*) FROM public.community_question WHERE deleted_at IS NOT NULL) deleted_questions,
(SELECT count(*) FROM public.community_answer WHERE deleted_at IS NOT NULL) deleted_answers
""",
                (rs, n) ->
                        new Stats(
                                rs.getLong("student_total"),
                                rs.getLong("active_students"),
                                rs.getLong("disabled_students"),
                                rs.getLong("question_total"),
                                rs.getLong("answer_total"),
                                rs.getLong("pending_questions"),
                                rs.getLong("pending_answers"),
                                rs.getLong("deleted_questions"),
                                rs.getLong("deleted_answers")));
    }
}
