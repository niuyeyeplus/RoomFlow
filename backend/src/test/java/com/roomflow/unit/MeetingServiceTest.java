package com.roomflow.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roomflow.common.enums.MeetingStatus;
import com.roomflow.common.enums.Role;
import com.roomflow.common.exception.BizException;
import com.roomflow.common.result.PageResult;
import com.roomflow.domain.account.Account;
import com.roomflow.domain.meeting.CreateMeetingRequest;
import com.roomflow.domain.meeting.Meeting;
import com.roomflow.domain.meeting.MeetingDetailVO;
import com.roomflow.domain.meeting.MeetingVO;
import com.roomflow.domain.participant.Participant;
import com.roomflow.domain.room.Room;
import com.roomflow.mapper.AccountMapper;
import com.roomflow.mapper.MeetingMapper;
import com.roomflow.mapper.ParticipantMapper;
import com.roomflow.mapper.RoomMapper;
import com.roomflow.security.LoginAccount;
import com.roomflow.service.MeetingService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z"); // 16:00 +08:00
  private static final LoginAccount USER = new LoginAccount(1L, "alice", Role.USER);

  @Mock private MeetingMapper meetingMapper;
  @Mock private RoomMapper roomMapper;
  @Mock private AccountMapper accountMapper;
  @Mock private ParticipantMapper participantMapper;

  private MeetingService service;

  @BeforeEach
  void setUp() {
    service =
        new MeetingService(
            meetingMapper, roomMapper, accountMapper, participantMapper, Clock.fixed(NOW, ZONE));
  }

  private static CreateMeetingRequest request(String start, String end) {
    CreateMeetingRequest r = new CreateMeetingRequest();
    r.setTitle("产品评审会");
    r.setRoomId(1L);
    r.setStartTime(OffsetDateTime.parse(start));
    r.setEndTime(OffsetDateTime.parse(end));
    return r;
  }

  private static Room enabledRoom() {
    Room room = new Room();
    room.setId(1L);
    room.setName("301会议室");
    room.setCapacity(8);
    room.setEnabled(true);
    return room;
  }

  @Test
  void createSucceedsAndAutoJoinsOrganizer() {
    when(roomMapper.selectOne(any())).thenReturn(enabledRoom());
    when(meetingMapper.selectCount(any())).thenReturn(0L);

    MeetingVO vo =
        service.create(request("2026-09-15T17:00:00+08:00", "2026-09-15T18:00:00+08:00"), USER);

    assertEquals("301会议室", vo.getRoomName());
    assertEquals("alice", vo.getOrganizerUsername());
    assertEquals(MeetingStatus.ACTIVE, vo.getStatus());
    assertEquals(1, vo.getParticipantCount());
    verify(meetingMapper).insert(argThat((Meeting m) -> m.getStatus() == MeetingStatus.ACTIVE));
    verify(participantMapper)
        .insert(
            argThat(
                (Participant p) ->
                    Boolean.TRUE.equals(p.getIsOrganizer()) && p.getAccountId().equals(1L)));
  }

  @Test
  void createRejectsNonBeijingOffset() {
    CreateMeetingRequest r = request("2026-09-15T09:00:00Z", "2026-09-15T10:00:00Z");
    BizException e = assertThrows(BizException.class, () -> service.create(r, USER));
    assertEquals(40001, e.getErrorCode().getCode());
  }

  @Test
  void createRejectsPastStart() {
    CreateMeetingRequest r = request("2026-09-15T15:00:00+08:00", "2026-09-15T16:00:00+08:00");
    BizException e = assertThrows(BizException.class, () -> service.create(r, USER));
    assertEquals(40905, e.getErrorCode().getCode());
  }

  @Test
  void createRejectsStartBeyond72h() {
    CreateMeetingRequest r = request("2026-09-18T16:15:00+08:00", "2026-09-18T17:15:00+08:00");
    BizException e = assertThrows(BizException.class, () -> service.create(r, USER));
    assertEquals(40905, e.getErrorCode().getCode());
  }

  @Test
  void createRejectsMissingRoom() {
    when(roomMapper.selectOne(any())).thenReturn(null);
    CreateMeetingRequest r = request("2026-09-15T17:00:00+08:00", "2026-09-15T18:00:00+08:00");
    BizException e = assertThrows(BizException.class, () -> service.create(r, USER));
    assertEquals(40401, e.getErrorCode().getCode());
  }

  @Test
  void createRejectsDisabledRoom() {
    Room room = enabledRoom();
    room.setEnabled(false);
    when(roomMapper.selectOne(any())).thenReturn(room);
    CreateMeetingRequest r = request("2026-09-15T17:00:00+08:00", "2026-09-15T18:00:00+08:00");
    BizException e = assertThrows(BizException.class, () -> service.create(r, USER));
    assertEquals(40906, e.getErrorCode().getCode());
  }

  @Test
  void createRejectsOverlappingMeeting() {
    when(roomMapper.selectOne(any())).thenReturn(enabledRoom());
    when(meetingMapper.selectCount(any())).thenReturn(1L);
    CreateMeetingRequest r = request("2026-09-15T17:00:00+08:00", "2026-09-15T18:00:00+08:00");
    BizException e = assertThrows(BizException.class, () -> service.create(r, USER));
    assertEquals(40902, e.getErrorCode().getCode());
  }

  @Test
  void listRejectsDeletedStatusFilter() {
    BizException e =
        assertThrows(
            BizException.class,
            () -> service.list(1, 10, null, null, MeetingStatus.DELETED, false, USER));
    assertEquals(40001, e.getErrorCode().getCode());
  }

  @Test
  void listRejectsBadPageAndSize() {
    assertThrows(BizException.class, () -> service.list(0, 10, null, null, null, false, USER));
    assertThrows(BizException.class, () -> service.list(1, 101, null, null, null, false, USER));
  }

  @Test
  void listReturnsEnrichedPage() {
    Meeting m = new Meeting();
    m.setId(10L);
    m.setRoomId(1L);
    m.setOrganizerId(1L);
    m.setTitle("t");
    m.setStartTime(LocalDateTime.of(2026, 9, 16, 10, 0));
    m.setEndTime(LocalDateTime.of(2026, 9, 16, 11, 0));
    m.setStatus(MeetingStatus.ACTIVE);
    Page<Meeting> page = new Page<>(1, 10);
    page.setRecords(List.of(m));
    page.setTotal(1);
    when(meetingMapper.selectPage(any(), any())).thenReturn(page);
    when(roomMapper.selectByIds(any())).thenReturn(List.of(enabledRoom()));
    Account alice = new Account();
    alice.setId(1L);
    alice.setUsername("alice");
    when(accountMapper.selectByIds(any())).thenReturn(List.of(alice));
    when(participantMapper.selectMaps(any()))
        .thenReturn(List.of(java.util.Map.of("meetingId", 10L, "cnt", 3L)));

    PageResult<MeetingVO> result = service.list(1, 10, null, null, null, false, USER);

    assertEquals(1, result.getTotal());
    assertEquals("301会议室", result.getRecords().get(0).getRoomName());
    assertEquals("alice", result.getRecords().get(0).getOrganizerUsername());
    assertEquals(3, result.getRecords().get(0).getParticipantCount());
  }

  @Test
  void getDetailRejectsDeletedMeeting() {
    Meeting m = new Meeting();
    m.setStatus(MeetingStatus.DELETED);
    when(meetingMapper.selectById(9L)).thenReturn(m);
    BizException e = assertThrows(BizException.class, () -> service.getDetail(9L));
    assertEquals(40401, e.getErrorCode().getCode());
  }

  @Test
  void getDetailRejectsMissingMeeting() {
    when(meetingMapper.selectById(9L)).thenReturn(null);
    BizException e = assertThrows(BizException.class, () -> service.getDetail(9L));
    assertEquals(40401, e.getErrorCode().getCode());
  }

  @Test
  void getDetailReturnsAllParticipantRecords() {
    Meeting m = new Meeting();
    m.setId(9L);
    m.setRoomId(1L);
    m.setOrganizerId(1L);
    m.setStatus(MeetingStatus.ACTIVE);
    m.setStartTime(LocalDateTime.of(2026, 9, 16, 10, 0));
    m.setEndTime(LocalDateTime.of(2026, 9, 16, 11, 0));
    when(meetingMapper.selectById(9L)).thenReturn(m);
    Participant p = new Participant();
    p.setId(1L);
    p.setMeetingId(9L);
    p.setAccountId(1L);
    p.setIsOrganizer(true);
    p.setJoinedAt(LocalDateTime.of(2026, 9, 15, 12, 0));
    when(participantMapper.selectList(any())).thenReturn(List.of(p));
    when(roomMapper.selectById(1L)).thenReturn(enabledRoom());
    Account alice = new Account();
    alice.setId(1L);
    alice.setUsername("alice");
    when(accountMapper.selectByIds(any())).thenReturn(List.of(alice));

    MeetingDetailVO vo = service.getDetail(9L);

    assertEquals(1, vo.getParticipants().size());
    assertEquals(1, vo.getParticipantCount());
  }

  @Test
  void listWithFiltersBuildsWithoutError() {
    Page<Meeting> page = new Page<>(1, 10);
    page.setRecords(List.of());
    when(meetingMapper.selectPage(any(), any())).thenReturn(page);
    PageResult<MeetingVO> result =
        service.list(2, 5, 1L, LocalDate.of(2026, 9, 16), MeetingStatus.ACTIVE, true, USER);
    assertEquals(2, result.getPage());
    assertEquals(5, result.getSize());
    verify(meetingMapper).selectPage(any(), any());
  }

  @Test
  void getDetailHandlesMissingRoomAndOrganizerOutsideParticipants() {
    Meeting m = new Meeting();
    m.setId(9L);
    m.setRoomId(1L);
    m.setOrganizerId(7L);
    m.setStatus(MeetingStatus.ACTIVE);
    m.setStartTime(LocalDateTime.of(2026, 9, 16, 10, 0));
    m.setEndTime(LocalDateTime.of(2026, 9, 16, 11, 0));
    when(meetingMapper.selectById(9L)).thenReturn(m);
    Participant p = new Participant();
    p.setId(1L);
    p.setMeetingId(9L);
    p.setAccountId(2L);
    p.setIsOrganizer(false);
    p.setJoinedAt(LocalDateTime.of(2026, 9, 15, 12, 0));
    when(participantMapper.selectList(any())).thenReturn(List.of(p));
    when(roomMapper.selectById(1L)).thenReturn(null); // historical room reference gone
    Account bob = new Account();
    bob.setId(2L);
    bob.setUsername("bob");
    Account org = new Account();
    org.setId(7L);
    org.setUsername("org");
    when(accountMapper.selectByIds(any())).thenReturn(List.of(bob, org));

    MeetingDetailVO vo = service.getDetail(9L);

    assertEquals("org", vo.getOrganizerUsername());
    assertEquals(1, vo.getParticipantCount());
    assertEquals("bob", vo.getParticipants().get(0).getUsername());
  }

  @Test
  void createTreatsNullConflictCountAsNoConflict() {
    when(roomMapper.selectOne(any())).thenReturn(enabledRoom());
    when(meetingMapper.selectCount(any())).thenReturn(null);

    MeetingVO vo =
        service.create(request("2026-09-15T17:00:00+08:00", "2026-09-15T18:00:00+08:00"), USER);

    assertEquals(MeetingStatus.ACTIVE, vo.getStatus());
    verify(meetingMapper).insert(any(Meeting.class));
  }

  @Test
  void requireVisibleMeetingRejectsNullAndMissing() {
    BizException e1 = assertThrows(BizException.class, () -> service.requireVisibleMeeting(null));
    assertEquals(40401, e1.getErrorCode().getCode());
    when(meetingMapper.selectById(9L)).thenReturn(null);
    BizException e2 = assertThrows(BizException.class, () -> service.requireVisibleMeeting(9L));
    assertEquals(40401, e2.getErrorCode().getCode());
  }

  @Test
  void createLocksRoomRowForUpdate() {
    when(roomMapper.selectOne(any())).thenReturn(enabledRoom());
    when(meetingMapper.selectCount(any())).thenReturn(0L);

    service.create(request("2026-09-15T17:00:00+08:00", "2026-09-15T18:00:00+08:00"), USER);

    verify(roomMapper)
        .selectOne(
            argThat(
                (com.baomidou.mybatisplus.core.conditions.Wrapper<Room> w) ->
                    w.getSqlSegment().contains("FOR UPDATE")));
  }

  @Test
  void requireVisibleMeetingForUpdateLocksMeetingRow() {
    Meeting m = new Meeting();
    m.setId(9L);
    m.setStatus(MeetingStatus.ACTIVE);
    when(meetingMapper.selectOne(any())).thenReturn(m);

    Meeting result = service.requireVisibleMeetingForUpdate(9L);

    assertEquals(9L, result.getId());
    verify(meetingMapper)
        .selectOne(
            argThat(
                (com.baomidou.mybatisplus.core.conditions.Wrapper<Meeting> w) ->
                    w.getSqlSegment().contains("FOR UPDATE")));
  }

  @Test
  void requireVisibleMeetingForUpdateRejectsDeleted() {
    Meeting m = new Meeting();
    m.setStatus(MeetingStatus.DELETED);
    when(meetingMapper.selectOne(any())).thenReturn(m);
    BizException e =
        assertThrows(BizException.class, () -> service.requireVisibleMeetingForUpdate(9L));
    assertEquals(40401, e.getErrorCode().getCode());
  }
}
