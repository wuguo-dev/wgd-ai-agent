package com.example.wjyaiagent.auth.controller;

import com.example.wjyaiagent.model.ApiErrorResponse;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Order(-1)
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> authenticationError(ResponseStatusException error) {
        String code = switch (error.getStatusCode().value()) {
            case 401 -> "UNAUTHORIZED";
            case 429 -> "RATE_LIMITED";
            case 409 -> "USERNAME_EXISTS";
            default -> "INVALID_REQUEST";
        };
        return ResponseEntity.status(error.getStatusCode())
                .body(new ApiErrorResponse(code, error.getReason(), null));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiErrorResponse> databaseUnavailable() {
        return ResponseEntity.status(503)
                .body(new ApiErrorResponse("AUTH_UNAVAILABLE", "登录服务暂时不可用", null));
    }
}
