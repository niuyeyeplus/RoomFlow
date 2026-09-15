package com.roomflow.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roomflow.common.enums.NotificationType;
import com.roomflow.common.exception.BizException;
import com.roomflow.common.exception.ErrorCode;
import com.roomflow.common.result.PageResult;
import com.roomflow.config.SecurityConfig;
import com.roomflow.domain.notification.NotificationVO;
import com.roomflow.security.JwtAuthenticationEntryPoint;
import com.roomflow.security.JwtAuthenticationFilter;
import com.roomflow.security.RestAccessDeniedHandler;
import com.roomflow.service.NotificationService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
@ActiveProfiles("test")
@Import({
  SecurityConfig.class,
  JwtAuthenticationFilter.class,
  JwtAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class
})
class NotificationControllerTest extends ControllerTestSupport {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private NotificationService notificationService;

  private static NotificationVO notification(long id, boolean read) {
    NotificationVO vo = new NotificationVO();
    vo.setId(id);
    vo.setType(NotificationType.PARTICIPANT_JOINED);
    vo.setTitle("t");
    vo.setContent("c");
    vo.setMeetingId(10L);
    vo.setIsRead(read);
    vo.setCreatedAt(OffsetDateTime.parse("2026-09-15T17:00:00+08:00"));
    return vo;
  }

  @Test
  void listReturnsPage() throws Exception {
    when(notificationService.list(any(), any(), eq(1), eq(10)))
        .thenReturn(new PageResult<>(List.of(notification(9001, false)), 1, 1, 10));
    mockMvc
        .perform(get("/api/notifications").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.records[0].id").value(9001));
  }

  @Test
  void listRejectsAnonymous() throws Exception {
    mockMvc
        .perform(get("/api/notifications"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  void markReadReturnsUpdated() throws Exception {
    when(notificationService.markRead(eq(9001L), any())).thenReturn(notification(9001, true));
    mockMvc
        .perform(patch("/api/notifications/9001/read").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.isRead").value(true));
  }

  @Test
  void markReadForeignNotificationIs404() throws Exception {
    when(notificationService.markRead(eq(9001L), any()))
        .thenThrow(new BizException(ErrorCode.NOT_FOUND));
    mockMvc
        .perform(patch("/api/notifications/9001/read").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
  }

  @Test
  void readAllReturnsCount() throws Exception {
    when(notificationService.markAllRead(any())).thenReturn(3);
    mockMvc
        .perform(patch("/api/notifications/read-all").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(3));
  }
}
