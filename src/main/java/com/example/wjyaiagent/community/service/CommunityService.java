package com.example.wjyaiagent.community.service;

import static com.example.wjyaiagent.common.request.RequestValidation.*;

import com.example.wjyaiagent.admin.dto.AdminDtos.CategoryState;
import com.example.wjyaiagent.admin.dto.AdminDtos.ContentState;
import com.example.wjyaiagent.admin.repository.AdminRepository;
import com.example.wjyaiagent.community.dto.CommunityDtos.*;
import com.example.wjyaiagent.community.repository.CommunityRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class CommunityService {
    private final CommunityRepository repository;
    private final AdminRepository audit;

    public CommunityService(CommunityRepository repository, AdminRepository audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public List<Category> categories() {
        return repository.categories();
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw CommunityException.invalid("内容为空或超出长度限制");
    }

    public Question adminQuestion(long id, long userId) {
        return repository.question(id, userId).orElseThrow(CommunityException::missing);
    }

    public Answer adminAnswer(long id) {
        return repository.answer(id).orElseThrow(CommunityException::missing);
    }

    public Question question(long id, long userId, boolean admin) {
        Question question = adminQuestion(id, userId);
        if (question.deletedAt() != null
                || (!"APPROVED".equals(question.status())
                        && question.author().id() != userId
                        && !admin)) throw CommunityException.missing();
        return question;
    }

    public Page<Question> publicQuestions(
            long userId, Long categoryId, String keyword, int page, int size) {
        pagination(page, size);
        var args = new ArrayList<Object>();
        String where = "q.status='APPROVED' AND q.deleted_at IS NULL";
        if (categoryId != null) {
            where += " AND q.category_id=?";
            args.add(categoryId);
        }
        if (keyword != null) {
            String search = search(keyword);
            if (!keyword.isBlank()) {
                where +=
                        " AND (LOWER(q.title) LIKE ? ESCAPE '!' OR LOWER(q.content) LIKE ? ESCAPE"
                                + " '!')";
                args.add(search);
                args.add(search);
            }
        }
        return repository.questions(userId, where, args, page, size);
    }

    public Page<Question> myQuestions(long userId, int page, int size) {
        pagination(page, size);
        return repository.questions(
                userId, "q.user_id=? AND q.deleted_at IS NULL", List.of(userId), page, size);
    }

    public Page<Question> favorites(long userId, int page, int size) {
        pagination(page, size);
        return repository.questions(
                userId,
                "q.status='APPROVED' AND q.deleted_at IS NULL AND EXISTS (SELECT 1 FROM"
                        + " public.community_favorite f WHERE f.question_id=q.id AND f.user_id=?)",
                List.of(userId),
                page,
                size);
    }

    @Transactional
    public Question createQuestion(long userId, QuestionInput input) {
        text(input.title(), 200);
        text(input.content(), 20000);
        Category category =
                repository
                        .category(input.categoryId(), true)
                        .orElseThrow(() -> CommunityException.invalid("分类不存在"));
        if (!"ENABLED".equals(category.status())) throw CommunityException.conflict("分类已停用");
        return question(repository.createQuestion(userId, input), userId, false);
    }

    public Page<Answer> answers(long id, long userId, boolean admin, int page, int size) {
        pagination(page, size);
        question(id, userId, admin);
        return repository.answers(
                "a.question_id=? AND a.status='APPROVED' AND q.status='APPROVED' AND a.deleted_at"
                        + " IS NULL AND q.deleted_at IS NULL",
                List.of(id),
                page,
                size);
    }

    public Page<Answer> myAnswers(long userId, int page, int size) {
        pagination(page, size);
        return repository.answers(
                "a.user_id=? AND a.deleted_at IS NULL AND q.deleted_at IS NULL",
                List.of(userId),
                page,
                size);
    }

    @Transactional
    public Answer createAnswer(long id, long userId, String content) {
        text(content, 10000);
        approved(id, userId);
        return adminAnswer(repository.createAnswer(userId, id, content));
    }

    private Question lockQuestion(long id, long userId) {
        repository.lockQuestion(id);
        return adminQuestion(id, userId);
    }

    private Question approved(long id, long userId) {
        Question question = lockQuestion(id, userId);
        if (question.deletedAt() != null || !"APPROVED".equals(question.status()))
            throw CommunityException.missing();
        return question;
    }

    private Answer lockAnswer(long id, long userId) {
        Answer answer = adminAnswer(id);
        repository.lockQuestion(answer.questionId());
        repository.lockAnswer(id);
        return adminAnswer(id);
    }

    @Transactional
    public void favorite(long id, long userId) {
        approved(id, userId);
        repository.favorite(userId, id);
    }

    @Transactional
    public void unfavorite(long id, long userId) {
        repository.unfavorite(userId, id);
    }

    public Page<Question> pendingQuestions(long admin, int page, int size) {
        return managedQuestions(admin, null, null, null, "PENDING", "ACTIVE", "NEWEST", page, size);
    }

    public Page<Answer> pendingAnswers(int page, int size) {
        pagination(page, size);
        return repository.answers(
                "a.status='PENDING' AND a.deleted_at IS NULL AND q.deleted_at IS NULL AND"
                        + " q.status='APPROVED'",
                List.of(),
                page,
                size);
    }

    public Page<Question> managedQuestions(
            long userId,
            String keyword,
            String author,
            Long categoryId,
            String status,
            String deleted,
            String sort,
            int page,
            int size) {
        pagination(page, size);
        boolean oldest = oldest(sort);
        var args = new ArrayList<Object>();
        String where = "1=1";
        if (keyword != null) {
            String search = search(keyword);
            if (!keyword.isBlank()) {
                where += " AND LOWER(q.title) LIKE ? ESCAPE '!'";
                args.add(search);
            }
        }
        if (author != null) {
            String search = search(author);
            if (!author.isBlank()) {
                where += " AND LOWER(u.username) LIKE ? ESCAPE '!'";
                args.add(search);
            }
        }
        if (categoryId != null) {
            if (categoryId < 1) throw CommunityException.invalid("分类标识必须为正整数");
            where += " AND q.category_id=?";
            args.add(categoryId);
        }
        where += statusFilter("q", status, args) + deletedFilter("q", deleted);
        return repository.questions(userId, where, args, page, size, oldest);
    }

    public Page<Answer> managedAnswers(
            Long questionId,
            String keyword,
            String author,
            String status,
            String deleted,
            String sort,
            int page,
            int size) {
        pagination(page, size);
        boolean oldest = oldest(sort);
        var args = new ArrayList<Object>();
        String where = "1=1";
        if (questionId != null) {
            if (questionId < 1) throw CommunityException.invalid("问题标识必须为正整数");
            where += " AND a.question_id=?";
            args.add(questionId);
        }
        if (keyword != null) {
            String search = search(keyword);
            if (!keyword.isBlank()) {
                where += " AND LOWER(q.title) LIKE ? ESCAPE '!'";
                args.add(search);
            }
        }
        if (author != null) {
            String search = search(author);
            if (!author.isBlank()) {
                where += " AND LOWER(u.username) LIKE ? ESCAPE '!'";
                args.add(search);
            }
        }
        where += statusFilter("a", status, args) + deletedFilter("a", deleted);
        return repository.answers(where, args, page, size, oldest);
    }

    private static String statusFilter(String alias, String status, List<Object> args) {
        validateChoice(status, Set.of("PENDING", "APPROVED", "REJECTED"));
        if (status == null) return "";
        args.add(status);
        return " AND " + alias + ".status=?";
    }

    private static String deletedFilter(String alias, String deleted) {
        validateChoice(deleted, Set.of("ALL", "ACTIVE", "DELETED"));
        if ("ACTIVE".equals(deleted)) return " AND " + alias + ".deleted_at IS NULL";
        if ("DELETED".equals(deleted)) return " AND " + alias + ".deleted_at IS NOT NULL";
        return "";
    }

    @Transactional
    public Question reviewQuestion(long id, long admin, boolean approve, String reason) {
        if (!approve) text(reason, 1000);
        Question before = lockQuestion(id, admin);
        if (before.deletedAt() != null) throw CommunityException.conflict("已删除问题不能审核");
        if (repository.reviewQuestion(
                        id,
                        admin,
                        approve ? "APPROVED" : "REJECTED",
                        approve ? null : reason.strip())
                != 1) throw CommunityException.conflict("该问题已被审核，请刷新列表");
        Question after = adminQuestion(id, admin);
        logQuestion(admin, approve ? "QUESTION_APPROVE" : "QUESTION_REJECT", reason, before, after);
        return after;
    }

    @Transactional
    public Answer reviewAnswer(long id, long admin, boolean approve, String reason) {
        if (!approve) text(reason, 1000);
        Answer before = lockAnswer(id, admin);
        Question parent = adminQuestion(before.questionId(), admin);
        if (before.deletedAt() != null
                || parent.deletedAt() != null
                || !"APPROVED".equals(parent.status()))
            throw CommunityException.conflict("回答或所属问题当前状态不允许审核");
        if (repository.reviewAnswer(
                        id,
                        admin,
                        approve ? "APPROVED" : "REJECTED",
                        approve ? null : reason.strip())
                != 1) throw CommunityException.conflict("该回答已被审核，请刷新列表");
        Answer after = adminAnswer(id);
        logAnswer(admin, approve ? "ANSWER_APPROVE" : "ANSWER_REJECT", reason, before, after);
        return after;
    }

    @Transactional
    public Question deleteQuestion(long id, long admin, String reason) {
        text(reason, 1000);
        Question before = lockQuestion(id, admin);
        if (repository.deleteQuestion(id, admin, reason.strip()) != 1)
            throw CommunityException.conflict("问题已删除");
        Question after = adminQuestion(id, admin);
        logQuestion(admin, "QUESTION_DELETE", reason.strip(), before, after);
        return after;
    }

    @Transactional
    public Question restoreQuestion(long id, long admin) {
        Question before = lockQuestion(id, admin);
        if (repository.restoreQuestion(id) != 1) throw CommunityException.conflict("问题未被删除");
        Question after = adminQuestion(id, admin);
        logQuestion(admin, "QUESTION_RESTORE", null, before, after);
        return after;
    }

    @Transactional
    public Answer deleteAnswer(long id, long admin, String reason) {
        text(reason, 1000);
        Answer before = lockAnswer(id, admin);
        if (repository.deleteAnswer(id, admin, reason.strip()) != 1)
            throw CommunityException.conflict("回答已删除");
        Answer after = adminAnswer(id);
        logAnswer(admin, "ANSWER_DELETE", reason.strip(), before, after);
        return after;
    }

    @Transactional
    public Answer restoreAnswer(long id, long admin) {
        Answer before = lockAnswer(id, admin);
        if (repository.restoreAnswer(id) != 1) throw CommunityException.conflict("回答未被删除");
        Answer after = adminAnswer(id);
        logAnswer(admin, "ANSWER_RESTORE", null, before, after);
        return after;
    }

    private void logQuestion(
            long operator, String action, String reason, Question before, Question after) {
        audit.log(
                operator,
                action,
                "QUESTION",
                after.id(),
                reason,
                new ContentState(
                        before.status(),
                        before.deletedAt(),
                        before.deletedBy(),
                        before.deleteReason()),
                new ContentState(
                        after.status(),
                        after.deletedAt(),
                        after.deletedBy(),
                        after.deleteReason()));
    }

    private void logAnswer(
            long operator, String action, String reason, Answer before, Answer after) {
        audit.log(
                operator,
                action,
                "ANSWER",
                after.id(),
                reason,
                new ContentState(
                        before.status(),
                        before.deletedAt(),
                        before.deletedBy(),
                        before.deleteReason()),
                new ContentState(
                        after.status(),
                        after.deletedAt(),
                        after.deletedBy(),
                        after.deleteReason()));
    }

    private static CategoryState categoryState(Category category) {
        return new CategoryState(
                category.name(), category.description(), category.sortOrder(), category.status());
    }

    @Transactional
    public Category createCategory(long operator, CategoryInput input) {
        text(input.name(), 80);
        Category after =
                repository
                        .category(repository.createCategory(input), false)
                        .orElseThrow(CommunityException::missing);
        audit.log(
                operator,
                "CATEGORY_CREATE",
                "CATEGORY",
                after.id(),
                null,
                null,
                categoryState(after));
        return after;
    }

    @Transactional
    public Category updateCategory(long operator, long id, CategoryInput input) {
        text(input.name(), 80);
        Category before = repository.category(id, true).orElseThrow(CommunityException::missing);
        repository.updateCategory(id, input);
        Category after = repository.category(id, false).orElseThrow(CommunityException::missing);
        audit.log(
                operator,
                "CATEGORY_UPDATE",
                "CATEGORY",
                id,
                null,
                categoryState(before),
                categoryState(after));
        return after;
    }

    @Transactional
    public Category categoryStatus(long operator, long id, String status) {
        validateChoice(status, Set.of("ENABLED", "DISABLED"));
        Category before = repository.category(id, true).orElseThrow(CommunityException::missing);
        if (before.status().equals(status)) throw CommunityException.conflict("分类已处于该状态");
        repository.categoryStatus(id, status);
        Category after = repository.category(id, false).orElseThrow(CommunityException::missing);
        audit.log(
                operator,
                "ENABLED".equals(status) ? "CATEGORY_ENABLE" : "CATEGORY_DISABLE",
                "CATEGORY",
                id,
                null,
                categoryState(before),
                categoryState(after));
        return after;
    }
}
