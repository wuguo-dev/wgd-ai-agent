package com.example.wjyaiagent.auth.controller;

import com.example.wjyaiagent.auth.dto.LoginRequest;
import com.example.wjyaiagent.auth.dto.RegisterRequest;
import com.example.wjyaiagent.auth.dto.RegisterResponse;
import com.example.wjyaiagent.auth.service.AuthService;
import com.example.wjyaiagent.user.dto.UserInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@io.swagger.v3.oas.annotations.security.SecurityScheme(name = "studentSession",
        type = io.swagger.v3.oas.annotations.enums.SecuritySchemeType.APIKEY,
        in = io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.COOKIE, paramName = "STUDENT_SESSION")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "参数或JSON无效"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "凭据错误或会话无效"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "权限或CSRF校验失败"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "登录尝试频繁"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "认证数据库不可用")
})
@Tag(name = "学生认证", description = "HttpOnly会话Cookie，写请求须携带/auth/csrf返回的CSRF请求头。会话最长30分钟。")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @Operation(summary = "注册学生账号，成功后需主动登录")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "用户名已存在")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest input,
                                     HttpServletRequest request, HttpServletResponse response) {
        return auth.register(input, request, response);
    }

    @GetMapping("/csrf")
    @Operation(summary = "获取CSRF令牌，登录及退出前调用，登录后令牌轮换")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    @Operation(summary = "学生用户名密码登录，凭证仅通过HttpOnly Cookie下发")
    public UserInfo login(@Valid @RequestBody LoginRequest input,
                          HttpServletRequest request, HttpServletResponse response) {
        return auth.login(input, request, response);
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "studentSession")
    @GetMapping("/me")
    @Operation(summary = "查询当前学生，未登录或会话过期返回401")
    public UserInfo me() {
        return auth.me();
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "studentSession")
    @PostMapping("/logout")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @Operation(summary = "立即销毁当前服务端会话并清除Cookie")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(request, response);
    }
}
