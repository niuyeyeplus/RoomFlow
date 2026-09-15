package com.roomflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roomflow.common.exception.ErrorCode;
import com.roomflow.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** Renders 401 responses as the contract Result body; distinguishes 40101 vs 40102. */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    Object attr = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTR);
    int code = attr instanceof Integer i ? i : ErrorCode.UNAUTHORIZED.getCode();
    ErrorCode error =
        code == ErrorCode.TOKEN_EXPIRED.getCode()
            ? ErrorCode.TOKEN_EXPIRED
            : ErrorCode.UNAUTHORIZED;
    response.setStatus(error.getHttpStatus());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(objectMapper.writeValueAsString(Result.error(code, error.getDefaultMessage())));
  }
}
