package com.example.wjyaiagent.admin.service;

import static com.example.wjyaiagent.common.request.RequestValidation.*;

import com.example.wjyaiagent.admin.dto.AdminDtos.*;
import com.example.wjyaiagent.admin.repository.AdminRepository;
import com.example.wjyaiagent.community.dto.CommunityDtos.Page;
import com.example.wjyaiagent.community.service.CommunityException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class AdminService {
    public static final Set<String> ACTIONS =
            Set.of(
                    "QUESTION_APPROVE",
                    "QUESTION_REJECT",
                    "QUESTION_DELETE",
                    "QUESTION_RESTORE",
                    "ANSWER_APPROVE",
                    "ANSWER_REJECT",
                    "ANSWER_DELETE",
                    "ANSWER_RESTORE",
                    "USER_ENABLE",
                    "USER_DISABLE",
                    "CATEGORY_CREATE",
                    "CATEGORY_UPDATE",
                    "CATEGORY_ENABLE",
                    "CATEGORY_DISABLE");
    private final AdminRepository repository;

    public AdminService(AdminRepository repository) {
        this.repository = repository;
    }

    public Stats stats() {
        return repository.stats();
    }

    public User user(long id) {
        return repository.user(id, false).orElseThrow(CommunityException::missing);
    }

    public Page<User> users(
            String keyword, String role, String status, String sort, int page, int size) {
        pagination(page, size);
        boolean oldest = oldest(sort);
        var args = new ArrayList<Object>();
        String where = "1=1";
        if (keyword != null) {
            String value = search(keyword);
            if (!keyword.isBlank()) {
                where += " AND LOWER(username) LIKE ? ESCAPE '!'";
                args.add(value);
            }
        }
        validateChoice(role, Set.of("STUDENT", "ADMIN"));
        validateChoice(status, Set.of("ACTIVE", "DISABLED"));
        if (role != null) {
            where += " AND role=?";
            args.add(role);
        }
        if (status != null) {
            where += " AND status=?";
            args.add(status);
        }
        return repository.users(where, args, page, size, oldest);
    }

    @Transactional
    public User userStatus(long id, long operator, UserStatusInput input) {
        User before = repository.user(id, true).orElseThrow(CommunityException::missing);
        if (id == operator || !"STUDENT".equals(before.role()))
            throw new CommunityException(403, "FORBIDDEN", "仅允许管理普通学生账号");
        validateChoice(input.status(), Set.of("ACTIVE", "DISABLED"));
        if (before.status().equals(input.status())) throw CommunityException.conflict("账号已处于该状态");
        if (repository.userStatus(id, before.status(), input.status()) != 1)
            throw CommunityException.conflict("账号状态已变化，请刷新");
        User after = user(id);
        repository.log(
                operator,
                "ACTIVE".equals(after.status()) ? "USER_ENABLE" : "USER_DISABLE",
                "USER",
                id,
                input.reason() == null ? null : input.reason().strip(),
                new UserState(before.status()),
                new UserState(after.status()));
        return after;
    }

    public Page<OperationLog> logs(
            Long operatorId, String action, Instant start, Instant end, int page, int size) {
        pagination(page, size);
        if (operatorId != null && operatorId < 1) throw CommunityException.invalid("管理员标识必须为正整数");
        if (start != null && end != null && start.isAfter(end))
            throw CommunityException.invalid("开始时间不能晚于结束时间");
        validateChoice(action, ACTIONS);
        var args = new ArrayList<Object>();
        String where = "1=1";
        if (operatorId != null) {
            where += " AND l.operator_id=?";
            args.add(operatorId);
        }
        if (action != null) {
            where += " AND l.action=?";
            args.add(action);
        }
        if (start != null) {
            where += " AND l.created_at>=?";
            args.add(java.sql.Timestamp.from(start));
        }
        if (end != null) {
            where += " AND l.created_at<=?";
            args.add(java.sql.Timestamp.from(end));
        }
        return repository.logs(where, args, page, size);
    }
}
