package com.example.wjyaiagent.exception;

import com.example.wjyaiagent.model.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.util.StringUtils;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(KnowledgeBaseException.class)
    public ResponseEntity<ApiErrorResponse> handleKnowledgeBaseException(KnowledgeBaseException error) {
        return errorResponse(statusOf(error.getCode()), error.getCode().name(), error.getMessage());
    }

    @ExceptionHandler(TransientAiException.class)
    public ResponseEntity<ApiErrorResponse> handleTransientAiException() {
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, KnowledgeBaseErrorCode.AI_UNAVAILABLE.name(),
                "模型服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<ApiErrorResponse> handleNonTransientAiException() {
        return errorResponse(HttpStatus.BAD_GATEWAY, KnowledgeBaseErrorCode.AI_ERROR.name(),
                "模型服务调用失败，请稍后重试");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolationException(ConstraintViolationException error) {
        String message = error.getConstraintViolations().stream()
                .map(violation -> violation.getMessage())
                .filter(StringUtils::hasText)
                .findFirst().orElse("参数校验失败");
        return errorResponse(HttpStatus.BAD_REQUEST, KnowledgeBaseErrorCode.INVALID_REQUEST.name(), message);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException error,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        String message = error.getBindingResult().getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .filter(StringUtils::hasText)
                .findFirst().orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(KnowledgeBaseErrorCode.INVALID_REQUEST.name(), message, null));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(@NonNull Exception error, Object body,
                                                             @NonNull HttpHeaders headers,
                                                             @NonNull HttpStatusCode statusCode,
                                                             @NonNull WebRequest request) {
        if (error instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiErrorResponse(KnowledgeBaseErrorCode.INVALID_REQUEST.name(),
                            "请求体格式不正确，需要 JSON", null));
        }
        return super.handleExceptionInternal(error, body, headers, statusCode, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception error, HttpServletRequest request) {
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, KnowledgeBaseErrorCode.INTERNAL_ERROR.name(),
                "知识库问答服务内部错误，请稍后重试");
    }

    static HttpStatus statusOf(KnowledgeBaseErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case AI_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case AI_ERROR -> HttpStatus.BAD_GATEWAY;
            case EVIDENCE_INSUFFICIENT, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static ResponseEntity<ApiErrorResponse> errorResponse(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(code, StringUtils.hasText(message) ? message : code, null));
    }
}
