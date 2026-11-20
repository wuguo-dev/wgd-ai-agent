package com.example.wjyaiagent.agent.controller;

import com.example.wjyaiagent.agent.service.AgentException;
import com.example.wjyaiagent.model.ApiErrorResponse;

import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(-1)
@RestControllerAdvice(assignableTypes = AgentChatController.class)
public class AgentExceptionHandler {
    @ExceptionHandler(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> invalidParameter() {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse("INVALID_REQUEST", "会话标识或分页参数格式不正确", null));
    }

    @ExceptionHandler(AgentException.class)
    public ResponseEntity<ApiErrorResponse> agent(AgentException error) {
        return ResponseEntity.status(error.status())
                .body(new ApiErrorResponse(error.code(), error.getMessage(), null));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> database() {
        return ResponseEntity.status(503)
                .body(new ApiErrorResponse("AGENT_UNAVAILABLE", "会话服务暂时不可用，请重试", null));
    }
}
