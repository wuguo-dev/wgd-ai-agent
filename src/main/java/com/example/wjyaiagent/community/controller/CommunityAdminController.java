package com.example.wjyaiagent.community.controller;

import com.example.wjyaiagent.admin.dto.AdminDtos.DeleteInput;
import com.example.wjyaiagent.auth.security.StudentPrincipal;
import com.example.wjyaiagent.community.dto.CommunityDtos.*;
import com.example.wjyaiagent.community.service.CommunityService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
public class CommunityAdminController {
    private final CommunityService service;

    public CommunityAdminController(CommunityService service) {
        this.service = service;
    }

    @GetMapping("/questions")
    public Page<Question> questions(
            @AuthenticationPrincipal StudentPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "ALL") String deleted,
            @RequestParam(defaultValue = "NEWEST") String sort) {
        return service.managedQuestions(
                principal.user().id(),
                keyword,
                author,
                categoryId,
                status,
                deleted,
                sort,
                page,
                size);
    }

    @PostMapping("/questions/{id}/approve")
    public Question approveQuestion(
            @AuthenticationPrincipal StudentPrincipal principal, @PathVariable long id) {
        return service.reviewQuestion(id, principal.user().id(), true, null);
    }

    @PostMapping("/questions/{id}/reject")
    public Question rejectQuestion(
            @AuthenticationPrincipal StudentPrincipal principal,
            @PathVariable long id,
            @Valid @RequestBody RejectInput input) {
        return service.reviewQuestion(id, principal.user().id(), false, input.reason());
    }

    @GetMapping("/answers")
    public Page<Answer> answers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long questionId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "ALL") String deleted,
            @RequestParam(defaultValue = "NEWEST") String sort) {
        return service.managedAnswers(
                questionId, keyword, author, status, deleted, sort, page, size);
    }

    @PostMapping("/answers/{id}/approve")
    public Answer approveAnswer(
            @AuthenticationPrincipal StudentPrincipal principal, @PathVariable long id) {
        return service.reviewAnswer(id, principal.user().id(), true, null);
    }

    @PostMapping("/answers/{id}/reject")
    public Answer rejectAnswer(
            @AuthenticationPrincipal StudentPrincipal principal,
            @PathVariable long id,
            @Valid @RequestBody RejectInput input) {
        return service.reviewAnswer(id, principal.user().id(), false, input.reason());
    }

    @GetMapping("/questions/{id}")
    public Question question(
            @AuthenticationPrincipal StudentPrincipal principal, @PathVariable long id) {
        return service.adminQuestion(id, principal.user().id());
    }

    @DeleteMapping("/questions/{id}")
    public Question deleteQuestion(
            @AuthenticationPrincipal StudentPrincipal principal,
            @PathVariable long id,
            @Valid @RequestBody DeleteInput input) {
        return service.deleteQuestion(id, principal.user().id(), input.reason());
    }

    @PostMapping("/questions/{id}/restore")
    public Question restoreQuestion(
            @AuthenticationPrincipal StudentPrincipal principal, @PathVariable long id) {
        return service.restoreQuestion(id, principal.user().id());
    }

    @GetMapping("/answers/{id}")
    public Answer answer(@PathVariable long id) {
        return service.adminAnswer(id);
    }

    @DeleteMapping("/answers/{id}")
    public Answer deleteAnswer(
            @AuthenticationPrincipal StudentPrincipal principal,
            @PathVariable long id,
            @Valid @RequestBody DeleteInput input) {
        return service.deleteAnswer(id, principal.user().id(), input.reason());
    }

    @PostMapping("/answers/{id}/restore")
    public Answer restoreAnswer(
            @AuthenticationPrincipal StudentPrincipal principal, @PathVariable long id) {
        return service.restoreAnswer(id, principal.user().id());
    }

    @GetMapping("/categories")
    public List<Category> categories() {
        return service.categories();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public Category createCategory(
            @AuthenticationPrincipal StudentPrincipal principal,
            @Valid @RequestBody CategoryInput input) {
        return service.createCategory(principal.user().id(), input);
    }

    @PutMapping("/categories/{id}")
    public Category updateCategory(
            @AuthenticationPrincipal StudentPrincipal principal,
            @PathVariable long id,
            @Valid @RequestBody CategoryInput input) {
        return service.updateCategory(principal.user().id(), id, input);
    }

    @PatchMapping("/categories/{id}/status")
    public Category status(
            @AuthenticationPrincipal StudentPrincipal principal,
            @PathVariable long id,
            @Valid @RequestBody StatusInput input) {
        return service.categoryStatus(principal.user().id(), id, input.status());
    }
}
