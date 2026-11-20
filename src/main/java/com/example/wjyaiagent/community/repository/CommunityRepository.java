package com.example.wjyaiagent.community.repository;

import com.example.wjyaiagent.community.dto.CommunityDtos.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class CommunityRepository {
    private final JdbcTemplate jdbc;
    private static final String QUESTION_FROM =
            " FROM public.community_question q JOIN public.community_users u ON u.id=q.user_id JOIN"
                    + " public.community_category c ON c.id=q.category_id ";
    private static final String QUESTION_COLUMNS =
            "SELECT q.*,u.username,c.name category_name,EXISTS(SELECT 1 FROM"
                + " public.community_favorite f WHERE f.question_id=q.id AND f.user_id=?) favorited"
                + " ";
    private static final String ANSWER_FROM =
            " FROM public.community_answer a JOIN public.community_users u ON u.id=a.user_id JOIN"
                    + " public.community_question q ON q.id=a.question_id ";
    private static final String ANSWER_COLUMNS =
            "SELECT a.*,u.username,q.title question_title,(q.deleted_at IS NOT NULL)"
                + " question_deleted ";

    public CommunityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant time(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableTime(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<Category> CATEGORY =
            (rs, n) ->
                    new Category(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("description"),
                            rs.getInt("sort_order"),
                            rs.getString("status"),
                            time(rs, "created_at"),
                            time(rs, "updated_at"));
    private static final RowMapper<Question> QUESTION =
            (rs, n) ->
                    new Question(
                            rs.getLong("id"),
                            rs.getString("title"),
                            rs.getString("content"),
                            new CategoryRef(
                                    rs.getLong("category_id"), rs.getString("category_name")),
                            new Author(rs.getLong("user_id"), rs.getString("username")),
                            rs.getString("status"),
                            rs.getString("reject_reason"),
                            time(rs, "created_at"),
                            time(rs, "updated_at"),
                            rs.getBoolean("favorited"),
                            nullableTime(rs, "deleted_at"),
                            (Long) rs.getObject("deleted_by"),
                            rs.getString("delete_reason"));
    private static final RowMapper<Answer> ANSWER =
            (rs, n) ->
                    new Answer(
                            rs.getLong("id"),
                            rs.getLong("question_id"),
                            rs.getString("question_title"),
                            rs.getString("content"),
                            new Author(rs.getLong("user_id"), rs.getString("username")),
                            rs.getString("status"),
                            rs.getString("reject_reason"),
                            time(rs, "created_at"),
                            time(rs, "updated_at"),
                            nullableTime(rs, "deleted_at"),
                            (Long) rs.getObject("deleted_by"),
                            rs.getString("delete_reason"),
                            rs.getBoolean("question_deleted"));

    public List<Category> categories() {
        return jdbc.query(
                "SELECT * FROM public.community_category ORDER BY sort_order,id", CATEGORY);
    }

    public Optional<Category> category(long id, boolean lock) {
        return jdbc
                .query(
                        "SELECT * FROM public.community_category WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        CATEGORY,
                        id)
                .stream()
                .findFirst();
    }

    public long createCategory(CategoryInput input) {
        return insert(
                "INSERT INTO public.community_category(name,description,sort_order) VALUES (?,?,?)",
                input.name().strip(),
                input.description() == null ? "" : input.description().strip(),
                input.sortOrder());
    }

    public int updateCategory(long id, CategoryInput input) {
        return jdbc.update(
                "UPDATE public.community_category SET"
                    + " name=?,description=?,sort_order=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                input.name().strip(),
                input.description() == null ? "" : input.description().strip(),
                input.sortOrder(),
                id);
    }

    public int categoryStatus(long id, String status) {
        return jdbc.update(
                "UPDATE public.community_category SET status=?,updated_at=CURRENT_TIMESTAMP WHERE"
                        + " id=?",
                status,
                id);
    }

    public long createQuestion(long userId, QuestionInput input) {
        return insert(
                "INSERT INTO public.community_question(user_id,category_id,title,content) VALUES"
                        + " (?,?,?,?)",
                userId,
                input.categoryId(),
                input.title().strip(),
                input.content().strip());
    }

    public Optional<Question> question(long id, long userId) {
        return jdbc
                .query(QUESTION_COLUMNS + QUESTION_FROM + " WHERE q.id=?", QUESTION, userId, id)
                .stream()
                .findFirst();
    }

    public void lockQuestion(long id) {
        jdbc.queryForList("SELECT id FROM public.community_question WHERE id=? FOR UPDATE", id);
    }

    public Page<Question> questions(
            long userId, String where, List<Object> args, int page, int size) {
        return questions(userId, where, args, page, size, false);
    }

    public Page<Question> questions(
            long userId, String where, List<Object> args, int page, int size, boolean oldest) {
        long total =
                jdbc.queryForObject(
                        "SELECT count(*)" + QUESTION_FROM + " WHERE " + where,
                        Long.class,
                        args.toArray());
        var queryArgs = new java.util.ArrayList<Object>();
        queryArgs.add(userId);
        queryArgs.addAll(args);
        queryArgs.add(size);
        queryArgs.add((long) (page - 1) * size);
        return new Page<>(
                jdbc.query(
                        QUESTION_COLUMNS
                                + QUESTION_FROM
                                + " WHERE "
                                + where
                                + (oldest
                                        ? " ORDER BY q.created_at ASC,q.id ASC LIMIT ? OFFSET ?"
                                        : " ORDER BY q.created_at DESC,q.id DESC LIMIT ? OFFSET ?"),
                        QUESTION,
                        queryArgs.toArray()),
                page,
                size,
                total);
    }

    public long createAnswer(long userId, long questionId, String content) {
        return insert(
                "INSERT INTO public.community_answer(user_id,question_id,content) VALUES (?,?,?)",
                userId,
                questionId,
                content.strip());
    }

    public Optional<Answer> answer(long id) {
        return jdbc.query(ANSWER_COLUMNS + ANSWER_FROM + " WHERE a.id=?", ANSWER, id).stream()
                .findFirst();
    }

    public Page<Answer> answers(String where, List<Object> args, int page, int size) {
        return answers(where, args, page, size, false);
    }

    public Page<Answer> answers(
            String where, List<Object> args, int page, int size, boolean oldest) {
        long total =
                jdbc.queryForObject(
                        "SELECT count(*)" + ANSWER_FROM + " WHERE " + where,
                        Long.class,
                        args.toArray());
        var queryArgs = new java.util.ArrayList<>(args);
        queryArgs.add(size);
        queryArgs.add((long) (page - 1) * size);
        return new Page<>(
                jdbc.query(
                        ANSWER_COLUMNS
                                + ANSWER_FROM
                                + " WHERE "
                                + where
                                + (oldest
                                        ? " ORDER BY a.created_at ASC,a.id ASC LIMIT ? OFFSET ?"
                                        : " ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?"),
                        ANSWER,
                        queryArgs.toArray()),
                page,
                size,
                total);
    }

    public int reviewQuestion(long id, long reviewer, String status, String reason) {
        return jdbc.update(
                "UPDATE public.community_question SET"
                    + " status=?,reject_reason=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP"
                    + " WHERE id=? AND status='PENDING' AND deleted_at IS NULL",
                status,
                reason,
                reviewer,
                id);
    }

    public int reviewAnswer(long id, long reviewer, String status, String reason) {
        return jdbc.update(
                "UPDATE public.community_answer SET"
                    + " status=?,reject_reason=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP"
                    + " WHERE id=? AND status='PENDING' AND deleted_at IS NULL",
                status,
                reason,
                reviewer,
                id);
    }

    public void favorite(long userId, long questionId) {
        jdbc.update(
                "INSERT INTO public.community_favorite(user_id,question_id) VALUES (?,?) ON"
                        + " CONFLICT DO NOTHING",
                userId,
                questionId);
    }

    public void unfavorite(long userId, long questionId) {
        jdbc.update(
                "DELETE FROM public.community_favorite WHERE user_id=? AND question_id=?",
                userId,
                questionId);
    }

    public void lockAnswer(long id) {
        jdbc.queryForList("SELECT id FROM public.community_answer WHERE id=? FOR UPDATE", id);
    }

    public int deleteQuestion(long id, long operator, String reason) {
        return jdbc.update(
                "UPDATE public.community_question SET"
                    + " deleted_at=CURRENT_TIMESTAMP,deleted_by=?,delete_reason=?,updated_at=CURRENT_TIMESTAMP"
                    + " WHERE id=? AND deleted_at IS NULL",
                operator,
                reason,
                id);
    }

    public int restoreQuestion(long id) {
        return jdbc.update(
                "UPDATE public.community_question SET"
                    + " deleted_at=NULL,deleted_by=NULL,delete_reason=NULL,updated_at=CURRENT_TIMESTAMP"
                    + " WHERE id=? AND deleted_at IS NOT NULL",
                id);
    }

    public int deleteAnswer(long id, long operator, String reason) {
        return jdbc.update(
                "UPDATE public.community_answer SET"
                    + " deleted_at=CURRENT_TIMESTAMP,deleted_by=?,delete_reason=?,updated_at=CURRENT_TIMESTAMP"
                    + " WHERE id=? AND deleted_at IS NULL",
                operator,
                reason,
                id);
    }

    public int restoreAnswer(long id) {
        return jdbc.update(
                "UPDATE public.community_answer SET"
                    + " deleted_at=NULL,deleted_by=NULL,delete_reason=NULL,updated_at=CURRENT_TIMESTAMP"
                    + " WHERE id=? AND deleted_at IS NOT NULL",
                id);
    }

    private long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    var statement = connection.prepareStatement(sql, new String[] {"id"});
                    for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
                    return statement;
                },
                keys);
        return java.util.Objects.requireNonNull(keys.getKey()).longValue();
    }
}
