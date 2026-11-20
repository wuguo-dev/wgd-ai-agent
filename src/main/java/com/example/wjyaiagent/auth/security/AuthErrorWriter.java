package com.example.wjyaiagent.auth.security;

import com.example.wjyaiagent.model.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class AuthErrorWriter {
    private AuthErrorWriter() { }

    public static void write(HttpServletResponse response, ObjectMapper mapper,
                             int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getWriter(), new ApiErrorResponse(code, message, null));
    }
}
