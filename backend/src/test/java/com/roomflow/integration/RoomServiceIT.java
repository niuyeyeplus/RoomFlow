package com.roomflow.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roomflow.common.enums.Role;
import com.roomflow.common.exception.BizException;
import com.roomflow.domain.account.Account;
import com.roomflow.domain.meeting.CreateMeetingRequest;
import com.roomflow.domain.meeting.Meeting;
import com.roomflow.domain.meeting.MeetingVO;
import com.roomflow.domain.room.CreateRoomRequest;
import com.roomflow.domain.room.Room;
import com.roomflow.domain.room.RoomStatusRequest;
import com.roomflow.domain.room.RoomVO;
import com.roomflow.mapper.AccountMapper;
import com.roomflow.mapper.MeetingMapper;
import com.roomflow.mapper.RoomMapper;
import com.roomflow.security.LoginAccount;
import com.roomflow.service.MeetingService;
import com.roomflow.service.RoomService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Room disable/delete semantics against real MySQL: disabling rejects while an unfinished ACTIVE
 * meeting exists; a disabled room rejects new bookings; delete is a soft disable that keeps the row
 * so historical meetings retain their room_id association.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class RoomServiceIT extends AbstractContainersIT {

  private static final ZoneOffset BEIJING = ZoneOffset.of("+08:00");

  @Autowired private RoomService roomService;
  @Autowired private MeetingService meetingService;
  @Autowired private AccountMapper accountMapper;
  @Autowired private RoomMapper roomMapper;
  @Autowired private MeetingMapper meetingMapper;

  private LoginAccount admin() {
    Account admin =
        accountMapper.selectOne(
            new LambdaQueryWrapper<Account>().eq(Account::getUsername, "admin"));
    return new LoginAccount(admin.getId(), admin.getUsername(), Role.ADMIN);
  }

  /** Creates a fresh enabled room via the service so each test owns its room (no overlap). */
  private RoomVO newRoom(String name) {
    CreateRoomRequest req = new CreateRoomRequest();
    req.setName(name);
    req.setCapacity(10);
    return roomService.create(req);
  }

  /** First 15-minute-aligned +08:00 slot strictly in the future. */
  private static OffsetDateTime nextSlot() {
    long minutes = Instant.now().getEpochSecond() / 60;
    long startMin = (minutes / 15 + 1) * 15;
    return OffsetDateTime.ofInstant(Instant.ofEpochSecond(startMin * 60), BEIJING);
  }

  private MeetingVO createMeeting(Long roomId) {
    OffsetDateTime start = nextSlot();
    CreateMeetingRequest req = new CreateMeetingRequest();
    req.setTitle("IT room admin meeting");
    req.setRoomId(roomId);
    req.setStartTime(start);
    req.setEndTime(start.plusHours(1));
    return meetingService.create(req, admin());
  }

  private static RoomStatusRequest status(boolean enabled) {
    RoomStatusRequest req = new RoomStatusRequest();
    req.setEnabled(enabled);
    return req;
  }

  @Test
  void disableRejectsWhileActiveMeetingUnfinished() {
    RoomVO room = newRoom("IT-disable-busy");
    createMeeting(room.getId());

    BizException e =
        assertThrows(
            BizException.class, () -> roomService.updateStatus(room.getId(), status(false)));
    assertEquals(40907, e.getErrorCode().getCode());
    // Rejected disable must leave the row untouched.
    assertTrue(roomMapper.selectById(room.getId()).getEnabled());
  }

  @Test
  void deleteRejectsWhileActiveMeetingUnfinished() {
    RoomVO room = newRoom("IT-delete-busy");
    createMeeting(room.getId());

    BizException e = assertThrows(BizException.class, () -> roomService.delete(room.getId()));
    assertEquals(40907, e.getErrorCode().getCode());
    assertTrue(roomMapper.selectById(room.getId()).getEnabled());
  }

  @Test
  void disabledRoomRejectsNewMeeting() {
    RoomVO room = newRoom("IT-disabled-room");
    roomService.updateStatus(room.getId(), status(false));

    BizException e = assertThrows(BizException.class, () -> createMeeting(room.getId()));
    assertEquals(40906, e.getErrorCode().getCode());
  }

  @Test
  void endedActiveMeetingDoesNotBlockDeleteAndRoomRowIsKept() {
    RoomVO room = newRoom("IT-history-room");
    MeetingVO meeting = createMeeting(room.getId());
    // Mark the ACTIVE meeting as finished (end_time <= now) so disable is allowed.
    Meeting row = meetingMapper.selectById(meeting.getId());
    row.setStartTime(LocalDateTime.now().minusHours(2));
    row.setEndTime(LocalDateTime.now().minusHours(1));
    meetingMapper.updateById(row);

    roomService.delete(room.getId());

    // Soft delete: the room row survives with enabled=0.
    Room stored = roomMapper.selectById(room.getId());
    assertNotNull(stored);
    assertFalse(stored.getEnabled());
    // Historical meeting keeps its room_id association.
    Meeting history = meetingMapper.selectById(meeting.getId());
    assertNotNull(history);
    assertEquals(room.getId(), history.getRoomId());
  }

  @Test
  void deleteIsIdempotentOnAlreadyDisabledRoom() {
    RoomVO room = newRoom("IT-idempotent");
    roomService.delete(room.getId());
    assertDoesNotThrow(() -> roomService.delete(room.getId()));
    assertFalse(roomMapper.selectById(room.getId()).getEnabled());
  }

  @Test
  void reEnableRestoresBooking() {
    RoomVO room = newRoom("IT-reenable");
    roomService.updateStatus(room.getId(), status(false));
    assertThrows(BizException.class, () -> createMeeting(room.getId()));

    RoomVO reenabled = roomService.updateStatus(room.getId(), status(true));
    assertTrue(reenabled.getEnabled());
    assertNotNull(createMeeting(room.getId()).getId());
  }
}
