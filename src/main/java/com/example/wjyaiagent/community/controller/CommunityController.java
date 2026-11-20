package com.example.wjyaiagent.community.controller;

import com.example.wjyaiagent.auth.security.StudentPrincipal;
import com.example.wjyaiagent.community.dto.CommunityDtos.*;
import com.example.wjyaiagent.community.service.CommunityService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/community")
public class CommunityController {
    private final CommunityService service;

    public CommunityController(CommunityService service) {
        this.service = service;
    }

    private static boolean admin(StudentPrincipal principal) {
        return "ADMIN".equals(principal.user().role());
    }

    @GetMapping("/categories")
    public List<Category> categories() {
        return service.categories();
    }

    @GetMapping("/questions")
    public Page<Question> questions(
            @AuthenticationPrincipal StudentPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword) {
        return service.publicQuestions(principal.user().id(), categoryId, keyword, page, size);
    }

    @PostMapping("/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public Question create(
            @AuthenticationPrincipal StudentPrincipal principal,
            @Valid @RequestBody QuestionInput input) {
        return service.createQuestion(principal.user().id(), input);
    }

    @GetMapping("/questions/{id}")
    public Question question(
            @AuthenticationPrincipal StudentPrincipal principal, @PathVariable long id) {
        return service.question(id, principal.user().id(), admin(principal));
    }

    @GetMapping("/questions/{id}/answers")
    public Page<Answer> answers(
            @AuthenticationPrincipal StudentPrincipal principal,
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.answers(id, principal.user().id(), admin(principal), page, size);
    }

    @PostMapping("/questions/{id}/answers")
    @ResponseStatus(HttpStatus.CREATED)
    public Answer answer(
            @AuthenticationPrincipal StudentPrincipal principal,
            @PathVariable long id,
            @Valid @RequestBody AnswerInput input) {
        return service.createAnswer(id, principal.user().id(), input.content());
    }

    @PostMapping("/questions/{id}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void favorite(
            @AuthenticationPrincipal StudentPrincipal principal, @PathVariable long id) {
        service.favorite(id, principal.user().id());
    }

    @DeleteMapping("/questions/{id}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfavorite(
            @AuthenticationPrincipal StudentPrincipal principal, @PathVariable long id) {
        service.unfavorite(id, principal.user().id());
    }

    @GetMapping("/me/questions")
    public Page<Question> mine(
            @AuthenticationPrincipal StudentPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.myQuestions(principal.user().id(), page, size);
    }

    @GetMapping("/me/answers")
    public Page<Answer> myAnswers(
            @AuthenticationPrincipal StudentPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.myAnswers(principal.user().id(), page, size);
    }

    @GetMapping("/me/favorites")
    public Page<Question> favorites(
            @AuthenticationPrincipal StudentPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.favorites(principal.user().id(), page, size);
    }
}
