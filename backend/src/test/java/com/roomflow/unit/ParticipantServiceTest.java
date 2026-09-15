package com.roomflow.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roomflow.common.enums.LeaveReason;
import com.roomflow.common.enums.MeetingStatus;
import com.roomflow.common.enums.NotificationType;
import com.roomflow.common.enums.Role;
import com.roomflow.common.exception.BizException;
import com.roomflow.common.exception.ErrorCode;
import com.roomflow.domain.meeting.Meeting;
import com.roomflow.domain.notification.NotificationMessage;
import com.roomflow.domain.participant.Participant;
import com.roomflow.domain.participant.ParticipantVO;
import com.roomflow.domain.room.Room;
import com.roomflow.mapper.AccountMapper;
import com.roomflow.mapper.ParticipantMapper;
import com.roomflow.mapper.RoomMapper;
import com.roomflow.mq.NotificationProducer;
import com.roomflow.security.LoginAccount;
import com.roomflow.service.MeetingService;
import com.roomflow.service.ParticipantService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ParticipantServiceTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z"); // 16:00 +08:00
  private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZONE);
  private static final LoginAccount USER = new LoginAccount(2L, "bob", Role.USER);
  private static final LoginAccount ORGANIZER = new LoginAccount(1L, "alice", Role.USER);
  private static final LoginAccount ADMIN = new LoginAccount(9L, "admin", Role.ADMIN);

  @Mock private MeetingService meetingService;
  @Mock private ParticipantMapper participantMapper;
  @Mock private RoomMapper roomMapper;
  @Mock private AccountMapper accountMapper;
  @Mock private NotificationProducer notificationProducer;

  private ParticipantService service;

  @BeforeEach
  void setUp() {
    service =
        new ParticipantService(
            meetingService,
            participantMapper,
            roomMapper,
            accountMapper,
            notificationProducer,
            Clock.fixed(NOW, ZONE));
  }

  private static Meeting meeting(MeetingStatus status, LocalDateTime start) {
    Meeting m = new Meeting();
    m.setId(10L);
    m.setTitle("评审会");
    m.setRoomId(1L);
    m.setOrganizerId(1L);
    m.setStatus(status);
    m.setStartTime(start);
    m.setEndTime(start.plusHours(1));
    return m;
  }

  private static Meeting futureActiveMeeting() {
    return meeting(MeetingStatus.ACTIVE, NOW_LOCAL.plusHours(1));
  }

  private static Participant participant(Long accountId) {
    Participant p = new Participant();
    p.setId(50L);
    p.setMeetingId(10L);
    p.setAccountId(accountId);
    p.setIsOrganizer(false);
    p.setBanned(false);
    p.setJoinedAt(NOW_LOCAL);
    return p;
  }

  private static int code(BizException e) {
    return e.getErrorCode().getCode();
  }

  // ---- join ----

  @Test
  void joinRejectsMissingMeeting() {
    when(meetingService.requireVisibleMeetingForUpdate(10L))
        .thenThrow(new BizException(ErrorCode.NOT_FOUND));
    BizException e = assertThrows(BizException.class, () -> service.join(10L, USER));
    assertEquals(40401, code(e));
  }

  @Test
  void joinRejectsDeletedMeeting() {
    when(meetingService.requireVisibleMeetingForUpdate(10L))
        .thenThrow(new BizException(ErrorCode.NOT_FOUND));
    BizException e = assertThrows(BizException.class, () -> service.join(10L, USER));
    assertEquals(40401, code(e));
  }

  @Test
  void joinRejectsCancelledMeeting() {
    when(meetingService.requireVisibleMeetingForUpdate(10L))
        .thenReturn(meeting(MeetingStatus.CANCELLED, NOW_LOCAL.plusHours(1)));
    BizException e = assertThrows(BizException.class, () -> service.join(10L, USER));
    assertEquals(40909, code(e));
  }

  @Test
  void joinRejectsStartedMeeting() {
    when(meetingService.requireVisibleMeetingForUpdate(10L))
        .thenReturn(meeting(MeetingStatus.ACTIVE, NOW_LOCAL.minusMinutes(5)));
    BizException e = assertThrows(BizException.class, () -> service.join(10L, USER));
    assertEquals(40909, code(e));
  }

  @Test
  void joinRejectsBannedAccount() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    Participant banned = participant(2L);
    banned.setBanned(true);
    banned.setLeftAt(NOW_LOCAL.minusMinutes(10));
    banned.setLeaveReason(LeaveReason.KICKED);
    when(participantMapper.selectOne(any())).thenReturn(banned);
    BizException e = assertThrows(BizException.class, () -> service.join(10L, USER));
    assertEquals(40904, code(e));
    verify(participantMapper, never()).insert(any(Participant.class));
    verify(participantMapper, never()).updateById(any(Participant.class));
  }

  @Test
  void joinRejectsDuplicateActiveRecord() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    when(participantMapper.selectOne(any())).thenReturn(participant(2L));
    BizException e = assertThrows(BizException.class, () -> service.join(10L, USER));
    assertEquals(40908, code(e));
  }

  @Test
  void joinRejectsAtCapacity() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    when(participantMapper.selectOne(any())).thenReturn(null);
    Room room = new Room();
    room.setId(1L);
    room.setCapacity(2);
    when(roomMapper.selectById(1L)).thenReturn(room);
    when(participantMapper.selectCount(any())).thenReturn(2L);
    BizException e = assertThrows(BizException.class, () -> service.join(10L, USER));
    assertEquals(40903, code(e));
  }

  @Test
  void joinInsertsNewRecordAndNotifiesOrganizer() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    when(participantMapper.selectOne(any())).thenReturn(null);
    Room room = new Room();
    room.setId(1L);
    room.setCapacity(8);
    when(roomMapper.selectById(1L)).thenReturn(room);
    when(participantMapper.selectCount(any())).thenReturn(1L);

    ParticipantVO vo = service.join(10L, USER);

    // Mutating path must read the meeting through the FOR UPDATE (row-locked) variant.
    verify(meetingService).requireVisibleMeetingForUpdate(10L);
    verify(participantMapper)
        .insert(
            org.mockito.ArgumentMatchers.<Participant>argThat(
                p -> p.getAccountId().equals(2L) && !Boolean.TRUE.equals(p.getIsOrganizer())));
    verify(notificationProducer)
        .send(
            org.mockito.ArgumentMatchers.argThat(
                (NotificationMessage m) ->
                    m.type() == NotificationType.PARTICIPANT_JOINED && m.accountId().equals(1L)));
    assertEquals(2L, vo.getAccountId());
    assertEquals("bob", vo.getUsername());
  }

  @Test
  void joinPublishesOrganizerNotificationOnlyAfterCommit() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    when(participantMapper.selectOne(any())).thenReturn(null);
    Room room = new Room();
    room.setId(1L);
    room.setCapacity(8);
    when(roomMapper.selectById(1L)).thenReturn(room);
    when(participantMapper.selectCount(any())).thenReturn(1L);

    // Simulate an active transaction: the publish must be deferred until afterCommit.
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.join(10L, USER);
      verify(notificationProducer, never()).send(any());
      for (TransactionSynchronization sync :
          TransactionSynchronizationManager.getSynchronizations()) {
        sync.afterCommit();
      }
      verify(notificationProducer)
          .send(
              org.mockito.ArgumentMatchers.argThat(
                  (NotificationMessage m) -> m.type() == NotificationType.PARTICIPANT_JOINED));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void joinStillSucceedsWhenNotificationPublishFails() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    when(participantMapper.selectOne(any())).thenReturn(null);
    Room room = new Room();
    room.setId(1L);
    room.setCapacity(8);
    when(roomMapper.selectById(1L)).thenReturn(room);
    when(participantMapper.selectCount(any())).thenReturn(1L);
    doThrow(new RuntimeException("amqp down")).when(notificationProducer).send(any());

    // A lost notification must not fail the committed join.
    ParticipantVO vo = service.join(10L, USER);
    assertNotNull(vo);
    assertEquals(2L, vo.getAccountId());
  }

  @Test
  void joinReusesUserLeftRow() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    Participant left = participant(2L);
    left.setLeftAt(NOW_LOCAL.minusMinutes(30));
    left.setLeaveReason(LeaveReason.USER_LEFT);
    when(participantMapper.selectOne(any())).thenReturn(left);
    Room room = new Room();
    room.setId(1L);
    room.setCapacity(8);
    when(roomMapper.selectById(1L)).thenReturn(room);
    when(participantMapper.selectCount(any())).thenReturn(1L);

    ParticipantVO vo = service.join(10L, USER);

    verify(participantMapper, never()).insert(any(Participant.class));
    verify(participantMapper).updateById(left);
    assertNull(left.getLeftAt());
    assertNull(left.getLeaveReason());
    assertEquals(50L, vo.getId());
    assertNotNull(vo.getJoinedAt());
  }

  // ---- leave ----

  @Test
  void leaveRejectsMissingMeeting() {
    when(meetingService.requireVisibleMeetingForUpdate(10L))
        .thenThrow(new BizException(ErrorCode.NOT_FOUND));
    BizException e = assertThrows(BizException.class, () -> service.leave(10L, USER));
    assertEquals(40401, code(e));
  }

  @Test
  void leaveRejectsNonActiveMeeting() {
    when(meetingService.requireVisibleMeetingForUpdate(10L))
        .thenReturn(meeting(MeetingStatus.ENDED, NOW_LOCAL.plusHours(1)));
    BizException e = assertThrows(BizException.class, () -> service.leave(10L, USER));
    assertEquals(40909, code(e));
  }

  @Test
  void leaveRejectsWhenNoActiveRecord() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    when(participantMapper.selectOne(any())).thenReturn(null);
    BizException e = assertThrows(BizException.class, () -> service.leave(10L, USER));
    assertEquals(40401, code(e));
  }

  @Test
  void leaveRejectsOrganizer() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    Participant organizerRow = participant(1L);
    organizerRow.setIsOrganizer(true);
    when(participantMapper.selectOne(any())).thenReturn(organizerRow);
    BizException e = assertThrows(BizException.class, () -> service.leave(10L, ORGANIZER));
    assertEquals(40909, code(e));
  }

  @Test
  void leaveSetsLeftAtAndUserLeftAndNotifies() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    Participant row = participant(2L);
    when(participantMapper.selectOne(any())).thenReturn(row);

    service.leave(10L, USER);

    assertNotNull(row.getLeftAt());
    assertEquals(LeaveReason.USER_LEFT, row.getLeaveReason());
    assertEquals(false, row.getBanned());
    verify(participantMapper).updateById(row);
    verify(notificationProducer)
        .send(
            org.mockito.ArgumentMatchers.argThat(
                (NotificationMessage m) ->
                    m.type() == NotificationType.PARTICIPANT_LEFT && m.accountId().equals(1L)));
  }

  // ---- kick ----

  @Test
  void kickRejectsMissingMeeting() {
    when(meetingService.requireVisibleMeetingForUpdate(10L))
        .thenThrow(new BizException(ErrorCode.NOT_FOUND));
    BizException e = assertThrows(BizException.class, () -> service.kick(10L, 2L, ORGANIZER));
    assertEquals(40401, code(e));
  }

  @Test
  void kickRejectsNonPrivilegedCaller() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    LoginAccount other = new LoginAccount(3L, "carol", Role.USER);
    BizException e = assertThrows(BizException.class, () -> service.kick(10L, 2L, other));
    assertEquals(40301, code(e));
  }

  @Test
  void kickRejectsNonActiveMeeting() {
    when(meetingService.requireVisibleMeetingForUpdate(10L))
        .thenReturn(meeting(MeetingStatus.CANCELLED, NOW_LOCAL.plusHours(1)));
    BizException e = assertThrows(BizException.class, () -> service.kick(10L, 2L, ORGANIZER));
    assertEquals(40909, code(e));
  }

  @Test
  void kickRejectsKickingOrganizer() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    BizException e = assertThrows(BizException.class, () -> service.kick(10L, 1L, ADMIN));
    assertEquals(40909, code(e));
  }

  @Test
  void kickRejectsTargetWithoutActiveRecord() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    when(participantMapper.selectOne(any())).thenReturn(null);
    BizException e = assertThrows(BizException.class, () -> service.kick(10L, 2L, ORGANIZER));
    assertEquals(40401, code(e));
  }

  @Test
  void kickSetsBannedAndKickedAndNotifiesTarget() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    Participant row = participant(2L);
    when(participantMapper.selectOne(any())).thenReturn(row);

    service.kick(10L, 2L, ORGANIZER);

    assertEquals(true, row.getBanned());
    assertEquals(LeaveReason.KICKED, row.getLeaveReason());
    assertNotNull(row.getLeftAt());
    verify(participantMapper).updateById(row);
    verify(notificationProducer)
        .send(
            org.mockito.ArgumentMatchers.argThat(
                (NotificationMessage m) ->
                    m.type() == NotificationType.PARTICIPANT_KICKED && m.accountId().equals(2L)));
  }

  @Test
  void kickAllowedByAdmin() {
    when(meetingService.requireVisibleMeetingForUpdate(10L)).thenReturn(futureActiveMeeting());
    Participant row = participant(2L);
    when(participantMapper.selectOne(any())).thenReturn(row);
    service.kick(10L, 2L, ADMIN);
    assertEquals(true, row.getBanned());
  }

  // ---- list ----

  @Test
  void listRejectsDeletedMeeting() {
    when(meetingService.requireVisibleMeeting(10L))
        .thenThrow(new BizException(ErrorCode.NOT_FOUND));
    BizException e = assertThrows(BizException.class, () -> service.list(10L));
    assertEquals(40401, code(e));
  }

  @Test
  void listReturnsRecords() {
    when(meetingService.requireVisibleMeeting(10L)).thenReturn(futureActiveMeeting());
    Participant row = participant(2L);
    when(participantMapper.selectList(any())).thenReturn(List.of(row));
    com.roomflow.domain.account.Account acc = new com.roomflow.domain.account.Account();
    acc.setId(2L);
    acc.setUsername("bob");
    when(accountMapper.selectByIds(any())).thenReturn(List.of(acc));
    List<ParticipantVO> result = service.list(10L);
    assertEquals(1, result.size());
    assertEquals("bob", result.get(0).getUsername());
  }
}
