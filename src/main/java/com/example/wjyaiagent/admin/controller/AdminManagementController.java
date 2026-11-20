package com.example.wjyaiagent.admin.controller;

import com.example.wjyaiagent.admin.dto.AdminDtos.*;
import com.example.wjyaiagent.admin.service.AdminService;
import com.example.wjyaiagent.auth.security.StudentPrincipal;
import com.example.wjyaiagent.community.dto.CommunityDtos.Page;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/admin")
public class AdminManagementController {
    private final AdminService service;

    public AdminManagementController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/dashboard/stats")
    public Stats stats() {
        return service.stats();
    }

    @GetMapping("/users")
    public Page<User> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "NEWEST") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.users(keyword, role, status, sort, page, size);
    }

    @GetMapping("/users/{id}")
    public User user(@PathVariable long id) {
        return service.user(id);
    }

    @PatchMapping("/users/{id}/status")
    public User status(
            @AuthenticationPrincipal StudentPrincipal principal,
            @PathVariable long id,
            @Valid @RequestBody UserStatusInput input) {
        return service.userStatus(id, principal.user().id(), input);
    }

    @GetMapping("/operation-logs")
    public Page<OperationLog> logs(
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant startAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant endAt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.logs(operatorId, action, startAt, endAt, page, size);
    }
}
