package com.roomflow.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roomflow.common.enums.MeetingStatus;
import com.roomflow.common.exception.BizException;
import com.roomflow.common.exception.ErrorCode;
import com.roomflow.common.result.PageResult;
import com.roomflow.config.SecurityConfig;
import com.roomflow.domain.meeting.MeetingDetailVO;
import com.roomflow.domain.meeting.MeetingVO;
import com.roomflow.security.JwtAuthenticationEntryPoint;
import com.roomflow.security.JwtAuthenticationFilter;
import com.roomflow.security.RestAccessDeniedHandler;
import com.roomflow.service.MeetingService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MeetingController.class)
@ActiveProfiles("test")
@Import({
  SecurityConfig.class,
  JwtAuthenticationFilter.class,
  JwtAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class
})
class MeetingControllerTest extends ControllerTestSupport {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private MeetingService meetingService;

  private static MeetingVO meetingVO() {
    MeetingVO vo = new MeetingVO();
    vo.setId(101L);
    vo.setTitle("产品评审会");
    vo.setRoomId(1L);
    vo.setRoomName("301会议室");
    vo.setOrganizerId(1L);
    vo.setOrganizerUsername("alice");
    vo.setStartTime(OffsetDateTime.parse("2026-09-16T10:00:00+08:00"));
    vo.setEndTime(OffsetDateTime.parse("2026-09-16T11:30:00+08:00"));
    vo.setStatus(MeetingStatus.ACTIVE);
    vo.setEndedEarly(false);
    vo.setParticipantCount(1);
    return vo;
  }

  @Test
  void listMeetingsReturnsPage() throws Exception {
    when(meetingService.list(anyInt(), anyInt(), any(), any(), any(), anyBoolean(), any()))
        .thenReturn(new PageResult<>(List.of(meetingVO()), 1, 1, 10));
    mockMvc
        .perform(get("/api/meetings").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.records[0].id").value(101))
        .andExpect(jsonPath("$.data.total").value(1));
  }

  @Test
  void listMeetingsRejectsAnonymous() throws Exception {
    mockMvc
        .perform(get("/api/meetings"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  void listMeetingsRejectsDeletedStatusFilter() throws Exception {
    // status=DELETED is a syntactically valid enum but forbidden as a filter value; the service
    // rejects it with PARAM_INVALID and this verifies the 400/40001 mapping.
    when(meetingService.list(anyInt(), anyInt(), any(), any(), any(), anyBoolean(), any()))
        .thenThrow(new BizException(ErrorCode.PARAM_INVALID));
    mockMvc
        .perform(get("/api/meetings?status=DELETED").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
  }

  @Test
  void getMeetingReturnsDetail() throws Exception {
    MeetingDetailVO vo = new MeetingDetailVO();
    vo.setId(101L);
    vo.setTitle("t");
    vo.setStatus(MeetingStatus.ACTIVE);
    vo.setParticipants(List.of());
    when(meetingService.getDetail(101L)).thenReturn(vo);
    mockMvc
        .perform(get("/api/meetings/101").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(101));
  }

  @Test
  void getMeetingReturns404ForDeleted() throws Exception {
    when(meetingService.getDetail(101L)).thenThrow(new BizException(ErrorCode.NOT_FOUND));
    mockMvc
        .perform(get("/api/meetings/101").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
  }

  @Test
  void createMeetingReturnsCreated() throws Exception {
    when(meetingService.create(any(), any())).thenReturn(meetingVO());
    mockMvc
        .perform(
            post("/api/meetings")
                .header("Authorization", bearer(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"评审\",\"roomId\":1,"
                        + "\"startTime\":\"2026-09-16T10:00:00+08:00\","
                        + "\"endTime\":\"2026-09-16T11:00:00+08:00\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.status").value("ACTIVE"));
  }

  @Test
  void createMeetingRejectsMissingFields() throws Exception {
    mockMvc
        .perform(
            post("/api/meetings")
                .header("Authorization", bearer(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\",\"roomId\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
  }

  @Test
  void createMeetingPropagatesConflict() throws Exception {
    when(meetingService.create(any(), any())).thenThrow(new BizException(ErrorCode.TIME_CONFLICT));
    mockMvc
        .perform(
            post("/api/meetings")
                .header("Authorization", bearer(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"评审\",\"roomId\":1,"
                        + "\"startTime\":\"2026-09-16T10:00:00+08:00\","
                        + "\"endTime\":\"2026-09-16T11:00:00+08:00\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40902));
  }

  // ---- PUT /api/meetings/{id} ----

  private static final String UPDATE_BODY =
      "{\"title\":\"评审（改）\",\"description\":\"d\",\"roomId\":1,"
          + "\"startTime\":\"2026-09-16T14:00:00+08:00\","
          + "\"endTime\":\"2026-09-16T15:00:00+08:00\"}";

  @Test
  void updateMeetingReturnsUpdated() throws Exception {
    when(meetingService.update(any(), any(), any())).thenReturn(meetingVO());
    mockMvc
        .perform(
            put("/api/meetings/101")
                .header("Authorization", bearer(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(101));
  }

  @Test
  void updateMeetingRejectsMissingFields() throws Exception {
    mockMvc
        .perform(
            put("/api/meetings/101")
                .header("Authorization", bearer(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
  }

  @Test
  void updateMeetingPropagatesForbiddenNotFoundAndState() throws Exception {
    // doThrow().when() (not when().thenThrow()) so re-stubbing never invokes the prior
    // throwing stub.
    org.mockito.Mockito.doThrow(new BizException(ErrorCode.FORBIDDEN))
        .when(meetingService)
        .update(any(), any(), any());
    mockMvc
        .perform(
            put("/api/meetings/101")
                .header("Authorization", bearer(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));

    org.mockito.Mockito.doThrow(new BizException(ErrorCode.NOT_FOUND))
        .when(meetingService)
        .update(any(), any(), any());
    mockMvc
        .perform(
            put("/api/meetings/101")
                .header("Authorization", bearer(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));

    org.mockito.Mockito.doThrow(new BizException(ErrorCode.STATE_NOT_ALLOWED))
        .when(meetingService)
        .update(any(), any(), any());
    mockMvc
        .perform(
            put("/api/meetings/101")
                .header("Authorization", bearer(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40909));
  }

  @Test
  void updateMeetingPropagatesRoomAndTimeConflicts() throws Exception {
    org.mockito.Mockito.doThrow(new BizException(ErrorCode.ROOM_DISABLED))
        .when(meetingService)
        .update(any(), any(), any());
    mockMvc
        .perform(
            put("/api/meetings/101")
                .header("Authorization", bearer(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40906));

    org.mockito.Mockito.doThrow(new BizException(ErrorCode.TIME_RULE))
        .when(meetingService)
        .update(any(), any(), any());
    mockMvc
        .perform(
            put("/api/meetings/101")
                .header("Authorization", bearer(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40905));
  }

  // ---- PATCH /api/meetings/{id}/cancel ----

  @Test
  void cancelMeetingReturnsCancelled() throws Exception {
    MeetingVO vo = meetingVO();
    vo.setStatus(MeetingStatus.CANCELLED);
    when(meetingService.cancel(any(), any())).thenReturn(vo);
    mockMvc
        .perform(patch("/api/meetings/101/cancel").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLED"));
  }

  @Test
  void cancelMeetingPropagates403404409() throws Exception {
    org.mockito.Mockito.doThrow(new BizException(ErrorCode.FORBIDDEN))
        .when(meetingService)
        .cancel(any(), any());
    mockMvc
        .perform(patch("/api/meetings/101/cancel").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));

    org.mockito.Mockito.doThrow(new BizException(ErrorCode.NOT_FOUND))
        .when(meetingService)
        .cancel(any(), any());
    mockMvc
        .perform(patch("/api/meetings/101/cancel").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));

    org.mockito.Mockito.doThrow(new BizException(ErrorCode.STATE_NOT_ALLOWED))
        .when(meetingService)
        .cancel(any(), any());
    mockMvc
        .perform(patch("/api/meetings/101/cancel").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40909));
  }

  // ---- DELETE /api/meetings/{id} ----

  @Test
  void deleteMeetingReturnsOk() throws Exception {
    mockMvc
        .perform(delete("/api/meetings/101").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void deleteMeetingPropagates403And404() throws Exception {
    org.mockito.Mockito.doThrow(new BizException(ErrorCode.FORBIDDEN))
        .when(meetingService)
        .delete(any(), any());
    mockMvc
        .perform(delete("/api/meetings/101").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));

    org.mockito.Mockito.doThrow(new BizException(ErrorCode.NOT_FOUND))
        .when(meetingService)
        .delete(any(), any());
    mockMvc
        .perform(delete("/api/meetings/101").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
  }

  // ---- PATCH /api/meetings/{id}/end-early ----

  @Test
  void endEarlyMeetingReturnsEnded() throws Exception {
    MeetingVO vo = meetingVO();
    vo.setStatus(MeetingStatus.ENDED);
    vo.setEndedEarly(true);
    when(meetingService.endEarly(any(), any())).thenReturn(vo);
    mockMvc
        .perform(patch("/api/meetings/101/end-early").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ENDED"))
        .andExpect(jsonPath("$.data.endedEarly").value(true));
  }

  @Test
  void endEarlyMeetingPropagates403404409() throws Exception {
    org.mockito.Mockito.doThrow(new BizException(ErrorCode.FORBIDDEN))
        .when(meetingService)
        .endEarly(any(), any());
    mockMvc
        .perform(patch("/api/meetings/101/end-early").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));

    org.mockito.Mockito.doThrow(new BizException(ErrorCode.NOT_FOUND))
        .when(meetingService)
        .endEarly(any(), any());
    mockMvc
        .perform(patch("/api/meetings/101/end-early").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));

    org.mockito.Mockito.doThrow(new BizException(ErrorCode.STATE_NOT_ALLOWED))
        .when(meetingService)
        .endEarly(any(), any());
    mockMvc
        .perform(patch("/api/meetings/101/end-early").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40909));
  }

  @Test
  void lifecycleEndpointsRejectAnonymous() throws Exception {
    mockMvc.perform(patch("/api/meetings/101/cancel")).andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/api/meetings/101")).andExpect(status().isUnauthorized());
    mockMvc.perform(patch("/api/meetings/101/end-early")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put("/api/meetings/101").contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
        .andExpect(status().isUnauthorized());
  }
}
