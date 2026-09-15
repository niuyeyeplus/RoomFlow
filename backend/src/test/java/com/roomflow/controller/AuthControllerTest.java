package com.roomflow.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roomflow.common.enums.Role;
import com.roomflow.common.exception.BizException;
import com.roomflow.common.exception.ErrorCode;
import com.roomflow.config.SecurityConfig;
import com.roomflow.domain.account.AccountVO;
import com.roomflow.domain.account.AuthTokenVO;
import com.roomflow.security.JwtAuthenticationEntryPoint;
import com.roomflow.security.JwtAuthenticationFilter;
import com.roomflow.security.RestAccessDeniedHandler;
import com.roomflow.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@ActiveProfiles("test")
@Import({
  SecurityConfig.class,
  JwtAuthenticationFilter.class,
  JwtAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class
})
class AuthControllerTest extends ControllerTestSupport {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private AccountService accountService;

  @Test
  void registerReturnsCreatedTokenPair() throws Exception {
    AuthTokenVO vo = new AuthTokenVO();
    vo.setAccessToken("at");
    vo.setAccessTokenExpiresIn(900);
    vo.setRefreshToken("rt");
    vo.setRefreshTokenExpiresIn(2592000);
    when(accountService.register(any())).thenReturn(vo);

    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.data.accessToken").value("at"));
  }

  @Test
  void registerRejectsBadUsernameFormat() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"a!\",\"password\":\"password123\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001))
        .andExpect(jsonPath("$.data.fieldErrors[0].field").value("username"));
  }

  @Test
  void registerRejectsDuplicateUsername() throws Exception {
    when(accountService.register(any())).thenThrow(new BizException(ErrorCode.USERNAME_EXISTS));
    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40901));
  }

  @Test
  void registerRejectsMalformedJson() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40002));
  }

  @Test
  void loginRejectsBadCredentials() throws Exception {
    when(accountService.login(any())).thenThrow(new BizException(ErrorCode.BAD_CREDENTIALS));
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40103));
  }

  @Test
  void loginRejectsDisabledAccount() throws Exception {
    when(accountService.login(any())).thenThrow(new BizException(ErrorCode.ACCOUNT_DISABLED));
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40302));
  }

  @Test
  void refreshRejectsInvalidToken() throws Exception {
    when(accountService.refresh(any())).thenThrow(new BizException(ErrorCode.REFRESH_INVALID));
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"bad\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40104));
  }

  @Test
  void meReturnsCurrentAccount() throws Exception {
    AccountVO vo = new AccountVO();
    vo.setId(1L);
    vo.setUsername("alice");
    vo.setRole(Role.USER);
    vo.setStatus(1);
    when(accountService.me(any())).thenReturn(vo);
    mockMvc
        .perform(get("/api/auth/me").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("alice"));
  }

  @Test
  void meRejectsMissingToken() throws Exception {
    mockMvc
        .perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  void meRejectsExpiredToken() throws Exception {
    mockMvc
        .perform(get("/api/auth/me").header("Authorization", bearer(EXPIRED_TOKEN)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40102));
  }

  @Test
  void meRejectsRevokedSession() throws Exception {
    mockMvc
        .perform(get("/api/auth/me").header("Authorization", bearer(REVOKED_TOKEN)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  void meRejectsBadSignature() throws Exception {
    mockMvc
        .perform(get("/api/auth/me").header("Authorization", bearer(BAD_TOKEN)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  void logoutRevokesSession() throws Exception {
    mockMvc
        .perform(post("/api/auth/logout").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    org.mockito.Mockito.verify(accountService).logout("s-user");
  }
}
