package com.roomflow.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roomflow.common.enums.Role;
import com.roomflow.common.exception.BizException;
import com.roomflow.domain.account.Account;
import com.roomflow.domain.meeting.CreateMeetingRequest;
import com.roomflow.domain.meeting.MeetingVO;
import com.roomflow.domain.room.Room;
import com.roomflow.mapper.AccountMapper;
import com.roomflow.mapper.RoomMapper;
import com.roomflow.security.LoginAccount;
import com.roomflow.service.MeetingService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the half-open [start,end) conflict semantics of MeetingService.create against a real
 * MySQL container: two meetings sharing exactly one boundary (end == start) must NOT conflict,
 * while any true overlap is rejected with 40902.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MeetingServiceIT extends AbstractContainersIT {

  private static final ZoneOffset BEIJING = ZoneOffset.of("+08:00");

  @Autowired private MeetingService meetingService;
  @Autowired private AccountMapper accountMapper;
  @Autowired private RoomMapper roomMapper;

  private LoginAccount admin() {
    Account admin =
        accountMapper.selectOne(
            new LambdaQueryWrapper<Account>().eq(Account::getUsername, "admin"));
    return new LoginAccount(admin.getId(), admin.getUsername(), Role.ADMIN);
  }

  /** Nth enabled room by id — tests use different rooms so their meetings never overlap. */
  private Long roomId(int index) {
    return roomMapper
        .selectList(
            new LambdaQueryWrapper<Room>().eq(Room::getEnabled, true).orderByAsc(Room::getId))
        .get(index)
        .getId();
  }

  /** First 15-minute-aligned +08:00 slot strictly in the future. */
  private static OffsetDateTime nextSlot() {
    long minutes = Instant.now().getEpochSecond() / 60;
    long startMin = (minutes / 15 + 1) * 15;
    return OffsetDateTime.ofInstant(Instant.ofEpochSecond(startMin * 60), BEIJING);
  }

  private static CreateMeetingRequest request(
      Long roomId, OffsetDateTime start, OffsetDateTime end) {
    CreateMeetingRequest r = new CreateMeetingRequest();
    r.setTitle("IT boundary meeting");
    r.setRoomId(roomId);
    r.setStartTime(start);
    r.setEndTime(end);
    return r;
  }

  @Test
  void adjacentBoundaryMeetingsDoNotConflict() {
    LoginAccount admin = admin();
    Long roomId = roomId(0);
    OffsetDateTime start = nextSlot();

    MeetingVO first = meetingService.create(request(roomId, start, start.plusHours(1)), admin);
    assertNotNull(first.getId());
    // end == start of the next meeting: the half-open interval must not conflict.
    MeetingVO second =
        meetingService.create(request(roomId, start.plusHours(1), start.plusHours(2)), admin);
    assertNotNull(second.getId());
  }

  @Test
  void overlappingMeetingIsRejected() {
    LoginAccount admin = admin();
    Long roomId = roomId(1);
    OffsetDateTime start = nextSlot();

    meetingService.create(request(roomId, start, start.plusHours(1)), admin);

    BizException e =
        assertThrows(
            BizException.class,
            () ->
                meetingService.create(
                    request(roomId, start.plusMinutes(30), start.plusMinutes(90)), admin));
    assertEquals(40902, e.getErrorCode().getCode());
  }
}
