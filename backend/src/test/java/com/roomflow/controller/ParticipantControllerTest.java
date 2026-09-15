package com.roomflow.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roomflow.common.exception.BizException;
import com.roomflow.common.exception.ErrorCode;
import com.roomflow.config.SecurityConfig;
import com.roomflow.domain.participant.ParticipantVO;
import com.roomflow.security.JwtAuthenticationEntryPoint;
import com.roomflow.security.JwtAuthenticationFilter;
import com.roomflow.security.RestAccessDeniedHandler;
import com.roomflow.service.ParticipantService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ParticipantController.class)
@ActiveProfiles("test")
@Import({
  SecurityConfig.class,
  JwtAuthenticationFilter.class,
  JwtAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class
})
class ParticipantControllerTest extends ControllerTestSupport {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ParticipantService participantService;

  @Test
  void joinReturnsParticipant() throws Exception {
    ParticipantVO vo = new ParticipantVO();
    vo.setId(503L);
    vo.setMeetingId(10L);
    vo.setAccountId(1L);
    vo.setUsername("alice");
    vo.setIsOrganizer(false);
    vo.setBanned(false);
    when(participantService.join(eq(10L), any())).thenReturn(vo);
    mockMvc
        .perform(post("/api/meetings/10/participants").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(503));
  }

  @Test
  void joinRejectsBanned() throws Exception {
    when(participantService.join(eq(10L), any()))
        .thenThrow(new BizException(ErrorCode.JOIN_BANNED));
    mockMvc
        .perform(post("/api/meetings/10/participants").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40904));
  }

  @Test
  void joinRejectsFull() throws Exception {
    when(participantService.join(eq(10L), any()))
        .thenThrow(new BizException(ErrorCode.CAPACITY_FULL));
    mockMvc
        .perform(post("/api/meetings/10/participants").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40903));
  }

  @Test
  void joinRejectsAnonymous() throws Exception {
    mockMvc
        .perform(post("/api/meetings/10/participants"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  void leaveReturnsOk() throws Exception {
    mockMvc
        .perform(
            delete("/api/meetings/10/participants/me").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void leaveRejectsOrganizer() throws Exception {
    doThrow(new BizException(ErrorCode.STATE_NOT_ALLOWED))
        .when(participantService)
        .leave(eq(10L), any());
    mockMvc
        .perform(
            delete("/api/meetings/10/participants/me").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40909));
  }

  @Test
  void kickReturnsOkForOrganizer() throws Exception {
    mockMvc
        .perform(
            delete("/api/meetings/10/participants/2").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    org.mockito.Mockito.verify(participantService).kick(eq(10L), eq(2L), any());
  }

  @Test
  void kickPropagatesForbidden() throws Exception {
    doThrow(new BizException(ErrorCode.FORBIDDEN))
        .when(participantService)
        .kick(eq(10L), eq(2L), any());
    mockMvc
        .perform(
            delete("/api/meetings/10/participants/2").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  void kickPropagatesNotFound() throws Exception {
    doThrow(new BizException(ErrorCode.NOT_FOUND))
        .when(participantService)
        .kick(eq(10L), eq(2L), any());
    mockMvc
        .perform(
            delete("/api/meetings/10/participants/2").header("Authorization", bearer(ADMIN_TOKEN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
  }

  @Test
  void listParticipantsReturnsAll() throws Exception {
    ParticipantVO vo = new ParticipantVO();
    vo.setId(501L);
    vo.setAccountId(1L);
    vo.setUsername("alice");
    when(participantService.list(10L)).thenReturn(List.of(vo));
    mockMvc
        .perform(get("/api/meetings/10/participants").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value(501));
  }
}
