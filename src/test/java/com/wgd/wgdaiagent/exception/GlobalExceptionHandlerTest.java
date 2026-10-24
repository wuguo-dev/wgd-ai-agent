package com.wgd.wgdaiagent.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgd.wgdaiagent.model.AgentErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T27（规格 §9.3）：{@code GlobalExceptionHandler} 的映射表逐行验证。
 *
 * <p>直接调用异常处理方法（同包可访问 protected 方法），不启动 Spring 上下文；控制器链路
 * 由 {@code LoveAgentControllerTest} 的 400/503 用例覆盖。</p>
 */
@Tag("offline")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---------- LoveAgentException：5 个错误码逐行验证 ----------

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "INVALID_REQUEST, 400",
            "AGENT_UNAVAILABLE, 503",
            "AGENT_ERROR, 502",
            "AGENT_LOOP_LIMIT, 500",
            "INTERNAL_ERROR, 500"
    })
    void loveAgentExceptionMapsToHttpStatus(AgentErrorCode code, int expectedStatus) {
        ResponseEntity<AgentErrorResponse> response = handler.handleLoveAgentException(
                new LoveAgentException(code, "测试错误"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code.name());
        assertThat(response.getBody().message()).isEqualTo("测试错误");
        assertThat(response.getBody().chatId()).isNull();
    }

    // ---------- 模型层异常 ----------

    @Test
    void transientAiExceptionMapsTo503Unavailable() {
        ResponseEntity<AgentErrorResponse> response = handler.handleTransientAiException(
                new TransientAiException("429 rate limit"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("AGENT_UNAVAILABLE");
        assertThat(response.getBody().message()).doesNotContain("429");
    }

    @Test
    void nonTransientAiExceptionMapsTo502AgentError() {
        ResponseEntity<AgentErrorResponse> response = handler.handleNonTransientAiException(
                new NonTransientAiException("401 unauthorized"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("AGENT_ERROR");
    }

    // ---------- 参数校验 ----------

    @Test
    void methodArgumentNotValidMapsTo400WithFieldMessage() throws Exception {
        MethodArgumentNotValidException exception = methodArgumentNotValid("message", "message 不能为空");

        ResponseEntity<Object> response = handler.handleMethodArgumentNotValid(
                exception, new HttpHeaders(), HttpStatus.BAD_REQUEST,
                new ServletWebRequest(new MockHttpServletRequest()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(AgentErrorResponse.class);
        AgentErrorResponse body = (AgentErrorResponse) response.getBody();
        assertThat(body.code()).isEqualTo("INVALID_REQUEST");
        assertThat(body.message()).isEqualTo("message 不能为空");
        assertThat(body.chatId()).isNull();
    }

    @Test
    void constraintViolationMapsTo400WithViolationMessage() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("chatId 只允许 1-64 位字母、数字、下划线或短横线");
        ConstraintViolationException exception = new ConstraintViolationException("bad", Set.of(violation));

        ResponseEntity<AgentErrorResponse> response = handler.handleConstraintViolationException(
                exception, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().message()).contains("chatId");
    }

    // ---------- 兜底 ----------

    @Test
    void unexpectedExceptionMapsTo500WithoutInternals() throws Exception {
        ResponseEntity<AgentErrorResponse> response = handler.handleUnexpectedException(
                new IllegalStateException("jdbc:postgresql://secret-host/db password=topsecret"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).isEqualTo("服务内部错误，请稍后重试");

        String json = objectMapper.writeValueAsString(response.getBody());
        assertThat(json)
                .doesNotContain("IllegalStateException")
                .doesNotContain("at com.")
                .doesNotContain("secret")
                .doesNotContain("jdbc")
                .doesNotContain("topsecret");
    }

    /** 造一个带 path 的 servlet 请求：日志需要 path，断言不依赖它。 */
    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/ai/love/agent/chat");
        return request;
    }

    /** 造一个「请求体字段校验失败」的异常：绑定结果是 message 字段的错误。 */
    private static MethodArgumentNotValidException methodArgumentNotValid(String field, String message)
            throws NoSuchMethodException {
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("sampleEndpoint", String.class), 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", field, null, false,
                new String[]{"NotBlank"}, null, message));
        return new MethodArgumentNotValidException(parameter, bindingResult);
    }

    @SuppressWarnings("unused")
    private void sampleEndpoint(String value) {
        // 仅用于构造 MethodParameter
    }
}
