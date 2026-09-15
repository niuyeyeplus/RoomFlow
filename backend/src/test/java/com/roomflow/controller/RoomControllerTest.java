package com.roomflow.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roomflow.common.enums.Equipment;
import com.roomflow.common.exception.BizException;
import com.roomflow.common.exception.ErrorCode;
import com.roomflow.config.SecurityConfig;
import com.roomflow.domain.room.RoomAvailabilityVO;
import com.roomflow.domain.room.RoomVO;
import com.roomflow.security.JwtAuthenticationEntryPoint;
import com.roomflow.security.JwtAuthenticationFilter;
import com.roomflow.security.RestAccessDeniedHandler;
import com.roomflow.service.RoomService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RoomController.class)
@ActiveProfiles("test")
@Import({
  SecurityConfig.class,
  JwtAuthenticationFilter.class,
  JwtAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class
})
class RoomControllerTest extends ControllerTestSupport {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private RoomService roomService;

  private static RoomVO roomVO(long id, boolean enabled) {
    RoomVO vo = new RoomVO();
    vo.setId(id);
    vo.setName("room-" + id);
    vo.setCapacity(8);
    vo.setEquipment(List.of(Equipment.PROJECTOR));
    vo.setEnabled(enabled);
    return vo;
  }

  @Test
  void listRoomsAuthenticated() throws Exception {
    when(roomService.list(null)).thenReturn(List.of(roomVO(1, true)));
    mockMvc
        .perform(get("/api/rooms").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value(1));
  }

  @Test
  void listRoomsRejectsAnonymous() throws Exception {
    mockMvc
        .perform(get("/api/rooms"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  void getRoomReturns404() throws Exception {
    when(roomService.get(9L)).thenThrow(new BizException(ErrorCode.NOT_FOUND));
    mockMvc
        .perform(get("/api/rooms/9").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
  }

  @Test
  void availabilityReturnsSlots() throws Exception {
    RoomAvailabilityVO vo = new RoomAvailabilityVO();
    vo.setRoomId(1L);
    vo.setStartDate(LocalDate.of(2026, 9, 16));
    vo.setEndDate(LocalDate.of(2026, 9, 16));
    vo.setOccupiedSlots(List.of());
    when(roomService.availability(eq(1L), any(), any())).thenReturn(vo);
    mockMvc
        .perform(
            get("/api/rooms/1/availability?startDate=2026-09-16")
                .header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.roomId").value(1));
  }

  @Test
  void availabilityRejectsMissingStartDate() throws Exception {
    mockMvc
        .perform(get("/api/rooms/1/availability").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40002));
  }

  @Test
  void createRoomAsAdmin() throws Exception {
    when(roomService.create(any())).thenReturn(roomVO(4, true));
    mockMvc
        .perform(
            post("/api/rooms")
                .header("Authorization", bearer(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"多功能厅B\",\"capacity\":20}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.name").value("room-4"));
  }

  @Test
  void createRoomAsUserForbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/rooms")
                .header("Authorization", bearer(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"capacity\":5}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  void createRoomRejectsBadCapacity() throws Exception {
    mockMvc
        .perform(
            post("/api/rooms")
                .header("Authorization", bearer(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"capacity\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
  }

  @Test
  void updateRoomAsAdmin() throws Exception {
    when(roomService.update(eq(1L), any())).thenReturn(roomVO(1, true));
    mockMvc
        .perform(
            put("/api/rooms/1")
                .header("Authorization", bearer(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"新名\",\"capacity\":10}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("room-1"));
  }

  @Test
  void updateStatusRejectsActiveMeetings() throws Exception {
    when(roomService.updateStatus(eq(1L), any()))
        .thenThrow(new BizException(ErrorCode.ROOM_HAS_ACTIVE_MEETING));
    mockMvc
        .perform(
            patch("/api/rooms/1/status")
                .header("Authorization", bearer(ADMIN_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40907));
  }

  @Test
  void deleteRoomAsAdmin() throws Exception {
    mockMvc
        .perform(delete("/api/rooms/1").header("Authorization", bearer(ADMIN_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void deleteRoomAsUserForbidden() throws Exception {
    mockMvc
        .perform(delete("/api/rooms/1").header("Authorization", bearer(USER_TOKEN)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  void deleteRoomPropagatesConflict() throws Exception {
    doThrow(new BizException(ErrorCode.ROOM_HAS_ACTIVE_MEETING)).when(roomService).delete(1L);
    mockMvc
        .perform(delete("/api/rooms/1").header("Authorization", bearer(ADMIN_TOKEN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40907));
  }

  @Test
  void updateRoomAsUserForbidden() throws Exception {
    mockMvc
        .perform(
            put("/api/rooms/1")
                .header("Authorization", bearer(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"capacity\":5}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  void updateStatusAsUserForbidden() throws Exception {
    mockMvc
        .perform(
            patch("/api/rooms/1/status")
                .header("Authorization", bearer(USER_TOKEN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }
}
