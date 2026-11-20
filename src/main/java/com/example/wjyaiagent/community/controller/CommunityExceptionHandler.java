package com.example.wjyaiagent.community.controller;

import com.example.wjyaiagent.community.service.CommunityException;
import com.example.wjyaiagent.model.ApiErrorResponse;

import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(-2)
@RestControllerAdvice(
        assignableTypes = {
            CommunityController.class,
            CommunityAdminController.class,
            com.example.wjyaiagent.admin.controller.AdminManagementController.class
        })
public class CommunityExceptionHandler {
    @ExceptionHandler(CommunityException.class)
    public ResponseEntity<ApiErrorResponse> business(CommunityException error) {
        return ResponseEntity.status(error.status())
                .body(new ApiErrorResponse(error.code(), error.getMessage(), null));
    }

    @ExceptionHandler(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> invalid() {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse("INVALID_REQUEST", "标识或分页参数格式错误", null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> conflict() {
        return ResponseEntity.status(409)
                .body(new ApiErrorResponse("CONFLICT", "分类名称已存在或内容状态冲突", null));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> unavailable() {
        return ResponseEntity.status(503)
                .body(new ApiErrorResponse("SERVICE_UNAVAILABLE", "社区服务暂时不可用，请稍后重试", null));
    }
}
