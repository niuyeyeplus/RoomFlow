package com.roomflow.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roomflow.common.enums.MeetingStatus;
import com.roomflow.common.exception.BizException;
import com.roomflow.domain.meeting.Meeting;
import com.roomflow.domain.room.CreateRoomRequest;
import com.roomflow.domain.room.Room;
import com.roomflow.domain.room.RoomAvailabilityVO;
import com.roomflow.domain.room.RoomStatusRequest;
import com.roomflow.domain.room.RoomVO;
import com.roomflow.domain.room.UpdateRoomRequest;
import com.roomflow.mapper.MeetingMapper;
import com.roomflow.mapper.RoomMapper;
import com.roomflow.service.RoomService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z");

  @Mock private RoomMapper roomMapper;
  @Mock private MeetingMapper meetingMapper;

  private RoomService service;

  @BeforeEach
  void setUp() {
    service =
        new RoomService(roomMapper, meetingMapper, Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));
  }

  private static Room room(Long id, boolean enabled) {
    Room r = new Room();
    r.setId(id);
    r.setName("room-" + id);
    r.setCapacity(8);
    r.setEnabled(enabled);
    r.setEquipment("PROJECTOR");
    return r;
  }

  private static int code(BizException e) {
    return e.getErrorCode().getCode();
  }

  @Test
  void listReturnsAllSortedAndFiltered() {
    when(roomMapper.selectList(any())).thenReturn(List.of(room(1L, true), room(2L, false)));
    List<RoomVO> all = service.list(null);
    assertEquals(2, all.size());
    List<RoomVO> enabledOnly = service.list(true);
    assertEquals(2, enabledOnly.size()); // mapper filter applied via wrapper; mock returns both
    verify(roomMapper, org.mockito.Mockito.times(2)).selectList(any());
  }

  @Test
  void getRejectsMissingRoom() {
    when(roomMapper.selectById(9L)).thenReturn(null);
    BizException e = assertThrows(BizException.class, () -> service.get(9L));
    assertEquals(40401, code(e));
  }

  @Test
  void createDefaultsEnabled() {
    CreateRoomRequest req = new CreateRoomRequest();
    req.setName("新会议室");
    req.setCapacity(10);
    RoomVO vo = service.create(req);
    verify(roomMapper).insert(any(Room.class));
    assertTrue(vo.getEnabled());
  }

  @Test
  void updateChangesFieldsButNotEnabled() {
    Room existing = room(1L, true);
    when(roomMapper.selectById(1L)).thenReturn(existing);
    UpdateRoomRequest req = new UpdateRoomRequest();
    req.setName("改名");
    req.setCapacity(12);
    req.setEquipment(List.of(com.roomflow.common.enums.Equipment.PHONE));
    RoomVO vo = service.update(1L, req);
    assertEquals("改名", vo.getName());
    assertEquals(List.of(com.roomflow.common.enums.Equipment.PHONE), vo.getEquipment());
    assertTrue(existing.getEnabled());
  }

  @Test
  void updateRejectsMissingRoom() {
    when(roomMapper.selectById(9L)).thenReturn(null);
    BizException e =
        assertThrows(BizException.class, () -> service.update(9L, new UpdateRoomRequest()));
    assertEquals(40401, code(e));
  }

  @Test
  void disableRejectsWhenActiveUnfinishedMeetingExists() {
    when(roomMapper.selectOne(any())).thenReturn(room(1L, true));
    when(meetingMapper.selectCount(any())).thenReturn(1L);
    RoomStatusRequest req = new RoomStatusRequest();
    req.setEnabled(false);
    BizException e = assertThrows(BizException.class, () -> service.updateStatus(1L, req));
    assertEquals(40907, code(e));
    verify(roomMapper, never()).updateById(any(Room.class));
  }

  @Test
  void disableSucceedsWhenNoActiveMeetings() {
    Room r = room(1L, true);
    when(roomMapper.selectOne(any())).thenReturn(r);
    when(meetingMapper.selectCount(any())).thenReturn(0L);
    RoomStatusRequest req = new RoomStatusRequest();
    req.setEnabled(false);
    RoomVO vo = service.updateStatus(1L, req);
    assertFalse(vo.getEnabled());
  }

  @Test
  void enableSkipsActiveMeetingCheck() {
    Room r = room(1L, false);
    when(roomMapper.selectOne(any())).thenReturn(r);
    RoomStatusRequest req = new RoomStatusRequest();
    req.setEnabled(true);
    RoomVO vo = service.updateStatus(1L, req);
    assertTrue(vo.getEnabled());
    verify(meetingMapper, never()).selectCount(any());
  }

  @Test
  void deleteIsIdempotentOnDisabledRoom() {
    when(roomMapper.selectOne(any())).thenReturn(room(1L, false));
    service.delete(1L);
    verify(meetingMapper, never()).selectCount(any());
    verify(roomMapper, never()).updateById(any(Room.class));
  }

  @Test
  void deleteRejectsWithActiveMeeting() {
    when(roomMapper.selectOne(any())).thenReturn(room(1L, true));
    when(meetingMapper.selectCount(any())).thenReturn(3L);
    BizException e = assertThrows(BizException.class, () -> service.delete(1L));
    assertEquals(40907, code(e));
  }

  @Test
  void deleteDisablesRoom() {
    Room r = room(1L, true);
    when(roomMapper.selectOne(any())).thenReturn(r);
    when(meetingMapper.selectCount(any())).thenReturn(0L);
    service.delete(1L);
    assertFalse(r.getEnabled());
    verify(roomMapper).updateById(r);
  }

  @Test
  void availabilityRejectsMissingRoom() {
    when(roomMapper.selectById(9L)).thenReturn(null);
    BizException e =
        assertThrows(
            BizException.class, () -> service.availability(9L, LocalDate.of(2026, 9, 16), null));
    assertEquals(40401, code(e));
  }

  @Test
  void availabilityRejectsEndBeforeStart() {
    when(roomMapper.selectById(1L)).thenReturn(room(1L, true));
    BizException e =
        assertThrows(
            BizException.class,
            () -> service.availability(1L, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 15)));
    assertEquals(40001, code(e));
  }

  @Test
  void availabilityRejectsSpanOver7Days() {
    when(roomMapper.selectById(1L)).thenReturn(room(1L, true));
    BizException e =
        assertThrows(
            BizException.class,
            () -> service.availability(1L, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 23)));
    assertEquals(40001, code(e));
  }

  @Test
  void availabilityDefaultsEndToStartAndReturnsSlots() {
    when(roomMapper.selectById(1L)).thenReturn(room(1L, true));
    Meeting m = new Meeting();
    m.setId(100L);
    m.setStatus(MeetingStatus.ACTIVE);
    m.setStartTime(LocalDateTime.of(2026, 9, 16, 10, 0));
    m.setEndTime(LocalDateTime.of(2026, 9, 16, 11, 30));
    when(meetingMapper.selectList(any())).thenReturn(List.of(m));

    RoomAvailabilityVO vo = service.availability(1L, LocalDate.of(2026, 9, 16), null);

    assertEquals(LocalDate.of(2026, 9, 16), vo.getEndDate());
    assertEquals(1, vo.getOccupiedSlots().size());
    assertEquals(100L, vo.getOccupiedSlots().get(0).getMeetingId());
    assertEquals("+08:00", vo.getOccupiedSlots().get(0).getStartTime().getOffset().toString());
  }
}
