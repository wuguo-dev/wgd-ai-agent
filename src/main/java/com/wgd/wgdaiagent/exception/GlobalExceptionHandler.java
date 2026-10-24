package com.wgd.wgdaiagent.exception;

import com.wgd.wgdaiagent.model.AgentErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
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

/**
 * 接口层统一异常处理（规格 §7.3 的映射表）。
 *
 * <p>响应体一律 {@link AgentErrorResponse}：{@code {"code","message","chatId"}}，其中
 * {@code chatId} 在异常处理阶段通常不可知，为 {@code null}；<b>永远不含堆栈或内部细节</b>。</p>
 *
 * <p>继承 {@link ResponseEntityExceptionHandler} 是为了让 404/405/406 等 MVC 框架异常继续使用
 * Spring 自己的状态语义（不会被下面的 {@link Exception} 兜底误判成 500）；只把
 * {@code MethodArgumentNotValidException} 覆盖成统一错误体。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String INVALID_REQUEST_MESSAGE = "参数校验失败";

    private static final String UNAVAILABLE_MESSAGE = "模型服务暂时不可用，请稍后重试";

    private static final String REJECTED_MESSAGE = "模型服务拒绝了请求，请稍后重试";

    private static final String INTERNAL_MESSAGE = "服务内部错误，请稍后重试";

    /** 内核业务异常：错误码 → HTTP 状态（§7.3 表）。 */
    @ExceptionHandler(LoveAgentException.class)
    public ResponseEntity<AgentErrorResponse> handleLoveAgentException(LoveAgentException error) {
        AgentErrorCode code = error.getCode();
        HttpStatus status = statusOf(code);
        logLoveAgentFailure(code, error);
        return errorResponse(status, code.name(), messageOrDefault(error.getMessage(), code.name()));
    }

    /** 模型暂时不可用（429/网络/超时，重试已耗尽）→ 503。 */
    @ExceptionHandler(TransientAiException.class)
    public ResponseEntity<AgentErrorResponse> handleTransientAiException(TransientAiException error) {
        log.error("模型服务暂时不可用（{}）", error.getClass().getSimpleName());
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                AgentErrorCode.AGENT_UNAVAILABLE.name(), UNAVAILABLE_MESSAGE);
    }

    /** 模型永久性失败（400/401/403）→ 502。 */
    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<AgentErrorResponse> handleNonTransientAiException(NonTransientAiException error) {
        log.error("模型服务永久性失败（{}）", error.getClass().getSimpleName());
        return errorResponse(HttpStatus.BAD_GATEWAY,
                AgentErrorCode.AGENT_ERROR.name(), REJECTED_MESSAGE);
    }

    /** 方法参数/路径参数上的约束校验失败 → 400。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<AgentErrorResponse> handleConstraintViolationException(ConstraintViolationException error) {
        String message = error.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(INVALID_REQUEST_MESSAGE);
        log.info("参数校验失败：{}", message);
        return errorResponse(HttpStatus.BAD_REQUEST, AgentErrorCode.INVALID_REQUEST.name(), message);
    }

    /**
     * 请求体 Bean Validation 失败 → 400；{@code message} 取第一个字段错误信息。
     *
     * <p>覆盖父类实现，只改错误体形状，状态码仍是 400。</p>
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException error,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        String message = error.getBindingResult().getAllErrors().stream()
                .map(GlobalExceptionHandler::defaultMessageOf)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(INVALID_REQUEST_MESSAGE);
        log.info("请求体校验失败：{}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AgentErrorResponse(AgentErrorCode.INVALID_REQUEST.name(), message, null));
    }

    /** 兜底：未预期异常 → 500，堆栈只进服务端日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentErrorResponse> handleUnexpectedException(Exception error) {
        log.error("未处理异常", error);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                AgentErrorCode.INTERNAL_ERROR.name(), INTERNAL_MESSAGE);
    }

    /** 错误码 → HTTP 状态（§7.3 表）。 */
    static HttpStatus statusOf(AgentErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case AGENT_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case AGENT_ERROR -> HttpStatus.BAD_GATEWAY;
            case AGENT_LOOP_LIMIT, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static ResponseEntity<AgentErrorResponse> errorResponse(HttpStatus status, String code, String message) {
        // 显式声明 JSON：SSE 端点可能带 Accept: text/event-stream，若走内容协商错误体会被丢弃（§7.3 要求建流前返回 400 JSON）
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AgentErrorResponse(code, message, null));
    }

    private static String messageOrDefault(String message, String fallback) {
        return StringUtils.hasText(message) ? message : fallback;
    }

    /** 字段/对象错误统一取 {@code defaultMessage}（请求体的校验注解都显式写了中文文案）。 */
    private static String defaultMessageOf(ObjectError error) {
        return error.getDefaultMessage();
    }

    /** 日志级别与堆栈策略（§7.4）：只有 500 类错误完整记堆栈，其余只记少量上下文。 */
    private static void logLoveAgentFailure(AgentErrorCode code, LoveAgentException error) {
        switch (code) {
            case INVALID_REQUEST -> log.info("请求不合法：{}", error.getMessage());
            case AGENT_UNAVAILABLE, AGENT_ERROR -> log.warn("agent 失败 code={} message={}",
                    code, error.getMessage());
            case AGENT_LOOP_LIMIT, INTERNAL_ERROR -> log.error("agent 失败 code={}", code, error);
        }
    }
}
