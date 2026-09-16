package com.roomflow.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roomflow.common.enums.MeetingStatus;
import com.roomflow.common.enums.NotificationType;
import com.roomflow.common.enums.Role;
import com.roomflow.common.exception.BizException;
import com.roomflow.common.result.PageResult;
import com.roomflow.domain.account.Account;
import com.roomflow.domain.meeting.CreateMeetingRequest;
import com.roomflow.domain.meeting.Meeting;
import com.roomflow.domain.meeting.MeetingDetailVO;
import com.roomflow.domain.meeting.MeetingVO;
import com.roomflow.domain.notification.NotificationMessage;
import com.roomflow.domain.participant.Participant;
import com.roomflow.domain.room.Room;
import com.roomflow.mapper.AccountMapper;
import com.roomflow.mapper.MeetingMapper;
import com.roomflow.mapper.ParticipantMapper;
import com.roomflow.mapper.RoomMapper;
import com.roomflow.mq.NotificationProducer;
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
  @Mock private NotificationProducer notificationProducer;

  // LambdaUpdateWrapper/LambdaQueryWrapper resolve SFunction columns through MyBatis-Plus
  // TableInfo metadata; a pure Mockito JVM never builds a SqlSessionFactory, so register the
  // entities explicitly (mapUnderscoreToCamelCase matches the app config).
  static {
    org.apache.ibatis.session.Configuration mybatisConfig =
        new org.apache.ibatis.session.Configuration();
    mybatisConfig.setMapUnderscoreToCamelCase(true);
    com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
        new org.apache.ibatis.builder.MapperBuilderAssistant(mybatisConfig, ""), Meeting.class);
    com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
        new org.apache.ibatis.builder.MapperBuilderAssistant(mybatisConfig, ""), Participant.class);
  }

  private MeetingService service;

  @BeforeEach
  void setUp() {
    service =
        new MeetingService(
            meetingMapper,
            roomMapper,
            accountMapper,
            participantMapper,
            notificationProducer,
            Clock.fixed(NOW, ZONE));
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

  // ==================== meeting lifecycle slice: update/cancel/delete/end-early
  // ====================

  private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZONE); // 16:00 +08:00
  private static final LoginAccount ORGANIZER = new LoginAccount(1L, "alice", Role.USER);
  private static final LoginAccount ADMIN = new LoginAccount(9L, "admin", Role.ADMIN);
  private static final LoginAccount OTHER = new LoginAccount(3L, "carol", Role.USER);

  private static Meeting meeting(MeetingStatus status, LocalDateTime start, LocalDateTime end) {
    Meeting m = new Meeting();
    m.setId(10L);
    m.setTitle("评审会");
    m.setRoomId(1L);
    m.setOrganizerId(1L);
    m.setStatus(status);
    m.setStartTime(start);
    m.setEndTime(end);
    m.setEndedEarly(false);
    return m;
  }

  /** ACTIVE meeting that has not started yet (starts 17:00, now is 16:00). */
  private static Meeting futureMeeting() {
    return meeting(MeetingStatus.ACTIVE, NOW_LOCAL.plusHours(1), NOW_LOCAL.plusHours(2));
  }

  /** ACTIVE meeting already in progress (15:00-17:00, now is 16:00). */
  private static Meeting runningMeeting() {
    return meeting(MeetingStatus.ACTIVE, NOW_LOCAL.minusHours(1), NOW_LOCAL.plusHours(1));
  }

  private static com.roomflow.domain.meeting.UpdateMeetingRequest updateRequest(
      Long roomId, String start, String end) {
    com.roomflow.domain.meeting.UpdateMeetingRequest r =
        new com.roomflow.domain.meeting.UpdateMeetingRequest();
    r.setTitle("改期评审会");
    r.setDescription("新说明");
    r.setRoomId(roomId);
    r.setStartTime(OffsetDateTime.parse(start));
    r.setEndTime(OffsetDateTime.parse(end));
    return r;
  }

  private static Account account(Long id, String username) {
    Account a = new Account();
    a.setId(id);
    a.setUsername(username);
    return a;
  }

  /** Stubs the lookups toVO performs after a successful mutation. */
  private void stubVoLookups() {
    when(roomMapper.selectById(1L)).thenReturn(enabledRoom());
    when(accountMapper.selectById(1L)).thenReturn(account(1L, "alice"));
    when(participantMapper.selectCount(any())).thenReturn(2L);
  }

  private static int code(BizException e) {
    return e.getErrorCode().getCode();
  }

  // ---- update ----

  @Test
  void updateRejectsMissingAndDeletedMeeting() {
    when(meetingMapper.selectOne(any())).thenReturn(null);
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                service.update(
                    10L,
                    updateRequest(1L, "2026-09-15T18:00:00+08:00", "2026-09-15T19:00:00+08:00"),
                    ORGANIZER));
    assertEquals(40401, code(e));

    when(meetingMapper.selectOne(any()))
        .thenReturn(meeting(MeetingStatus.DELETED, NOW_LOCAL.plusHours(1), NOW_LOCAL.plusHours(2)));
    e =
        assertThrows(
            BizException.class,
            () ->
                service.update(
                    10L,
                    updateRequest(1L, "2026-09-15T18:00:00+08:00", "2026-09-15T19:00:00+08:00"),
                    ORGANIZER));
    assertEquals(40401, code(e));
  }

  @Test
  void updateRejectsNonPrivilegedCaller() {
    when(meetingMapper.selectOne(any())).thenReturn(futureMeeting());
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                service.update(
                    10L,
                    updateRequest(1L, "2026-09-15T18:00:00+08:00", "2026-09-15T19:00:00+08:00"),
                    OTHER));
    assertEquals(40301, code(e));
  }

  @Test
  void updateRejectsEndedAndCancelled() {
    for (MeetingStatus status :
        new MeetingStatus[] {MeetingStatus.ENDED, MeetingStatus.CANCELLED}) {
      when(meetingMapper.selectOne(any()))
          .thenReturn(meeting(status, NOW_LOCAL.plusHours(1), NOW_LOCAL.plusHours(2)));
      BizException e =
          assertThrows(
              BizException.class,
              () ->
                  service.update(
                      10L,
                      updateRequest(1L, "2026-09-15T18:00:00+08:00", "2026-09-15T19:00:00+08:00"),
                      ORGANIZER));
      assertEquals(40909, code(e));
    }
  }

  @Test
  void updateNotStartedSucceedsWithFullFieldChange() {
    Meeting m = futureMeeting();
    when(meetingMapper.selectOne(any())).thenReturn(m);
    Room newRoom = enabledRoom();
    newRoom.setId(2L);
    newRoom.setCapacity(12);
    when(roomMapper.selectOne(any())).thenReturn(newRoom);
    when(meetingMapper.selectCount(any())).thenReturn(0L);
    when(participantMapper.selectCount(any())).thenReturn(2L); // capacity check + toVO
    when(roomMapper.selectById(2L)).thenReturn(newRoom);
    when(accountMapper.selectById(1L)).thenReturn(account(1L, "alice"));

    MeetingVO vo =
        service.update(
            10L,
            updateRequest(2L, "2026-09-15T19:00:00+08:00", "2026-09-15T20:00:00+08:00"),
            ORGANIZER);

    assertEquals("改期评审会", m.getTitle());
    assertEquals(2L, m.getRoomId());
    assertEquals(LocalDateTime.of(2026, 9, 15, 19, 0), m.getStartTime());
    assertEquals(2, vo.getParticipantCount());
    // updateById cannot write a null description (NOT_NULL strategy), so the service persists
    // through an explicit LambdaUpdateWrapper SET clause instead.
    verify(meetingMapper)
        .update(
            isNull(),
            argThat(
                w ->
                    w
                            instanceof
                            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                                        Meeting>
                                    uw
                        && uw.getSqlSet().contains("room_id=")
                        && uw.getSqlSet().contains("start_time=")
                        && uw.getSqlSet().contains("updated_at=")));
  }

  @Test
  void updateAllowedByAdminOnOthersMeeting() {
    Meeting m = futureMeeting();
    when(meetingMapper.selectOne(any())).thenReturn(m);
    when(roomMapper.selectOne(any())).thenReturn(enabledRoom());
    when(meetingMapper.selectCount(any())).thenReturn(0L);
    stubVoLookups();

    MeetingVO vo =
        service.update(
            10L,
            updateRequest(1L, "2026-09-15T18:00:00+08:00", "2026-09-15T19:00:00+08:00"),
            ADMIN);

    assertEquals("改期评审会", vo.getTitle());
    verify(meetingMapper).update(isNull(), any());
  }

  @Test
  void updateStartedMeetingAllowsTitleDescriptionOnly() {
    Meeting m = runningMeeting();
    when(meetingMapper.selectOne(any())).thenReturn(m);
    stubVoLookups();

    // Same roomId/startTime/endTime as stored; time rules would reject this past start —
    // success proves they are skipped for started meetings.
    MeetingVO vo =
        service.update(
            10L,
            updateRequest(1L, "2026-09-15T15:00:00+08:00", "2026-09-15T17:00:00+08:00"),
            ORGANIZER);

    assertEquals("改期评审会", m.getTitle());
    assertEquals("新说明", m.getDescription());
    // The started branch must not SET schedule columns — only title/description/updated_at.
    verify(meetingMapper)
        .update(
            isNull(),
            argThat(
                w ->
                    w
                            instanceof
                            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                                        Meeting>
                                    uw
                        && uw.getSqlSet().contains("title=")
                        && uw.getSqlSet().contains("description=")
                        && !uw.getSqlSet().contains("room_id=")
                        && !uw.getSqlSet().contains("start_time=")));
    verify(roomMapper, never()).selectOne(any());
    verify(meetingMapper, never()).selectCount(any());
  }

  @Test
  void updateStartedMeetingRejectsScheduleFieldChanges() {
    Meeting m = runningMeeting();
    when(meetingMapper.selectOne(any())).thenReturn(m);

    // roomId differs
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                service.update(
                    10L,
                    updateRequest(2L, "2026-09-15T15:00:00+08:00", "2026-09-15T17:00:00+08:00"),
                    ORGANIZER));
    assertEquals(40909, code(e));
    // startTime differs
    e =
        assertThrows(
            BizException.class,
            () ->
                service.update(
                    10L,
                    updateRequest(1L, "2026-09-15T15:15:00+08:00", "2026-09-15T17:00:00+08:00"),
                    ORGANIZER));
    assertEquals(40909, code(e));
    // endTime differs
    e =
        assertThrows(
            BizException.class,
            () ->
                service.update(
                    10L,
                    updateRequest(1L, "2026-09-15T15:00:00+08:00", "2026-09-15T17:15:00+08:00"),
                    ORGANIZER));
    assertEquals(40909, code(e));
  }

  @Test
  void updateRejectsNonBeijingOffset() {
    when(meetingMapper.selectOne(any())).thenReturn(futureMeeting());
    com.roomflow.domain.meeting.UpdateMeetingRequest r =
        updateRequest(1L, "2026-09-15T10:00:00Z", "2026-09-15T11:00:00Z");
    BizException e = assertThrows(BizException.class, () -> service.update(10L, r, ORGANIZER));
    assertEquals(40001, code(e));
  }

  @Test
  void updateRejectsInvalidNewTimeWindow() {
    when(meetingMapper.selectOne(any())).thenReturn(futureMeeting());
    // New start in the past -> 40905 (full time rules apply to not-started meetings).
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                service.update(
                    10L,
                    updateRequest(1L, "2026-09-15T14:00:00+08:00", "2026-09-15T15:00:00+08:00"),
                    ORGANIZER));
    assertEquals(40905, code(e));
  }

  @Test
  void updateRejectsMissingRoom() {
    when(meetingMapper.selectOne(any())).thenReturn(futureMeeting());
    when(roomMapper.selectOne(any())).thenReturn(null);
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                service.update(
                    10L,
                    updateRequest(1L, "2026-09-15T18:00:00+08:00", "2026-09-15T19:00:00+08:00"),
                    ORGANIZER));
    assertEquals(40401, code(e));
  }

  @Test
  void updateRejectsDisabledNewRoom() {
    when(meetingMapper.selectOne(any())).thenReturn(futureMeeting());
    Room disabled = enabledRoom();
    disabled.setId(2L);
    disabled.setEnabled(false);
    when(roomMapper.selectOne(any())).thenReturn(disabled);
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                service.update(
                    10L,
                    updateRequest(2L, "2026-09-15T18:00:00+08:00", "2026-09-15T19:00:00+08:00"),
                    ORGANIZER));
    assertEquals(40906, code(e));
  }

  @Test
  void updateRejectsNewRoomSmallerThanActiveParticipants() {
    when(meetingMapper.selectOne(any())).thenReturn(futureMeeting());
    Room small = enabledRoom();
    small.setId(2L);
    small.setCapacity(2);
    when(roomMapper.selectOne(any())).thenReturn(small);
    when(meetingMapper.selectCount(any())).thenReturn(0L); // no schedule conflict
    when(participantMapper.selectCount(any())).thenReturn(3L); // 3 active participants > 2 seats
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                service.update(
                    10L,
                    updateRequest(2L, "2026-09-15T18:00:00+08:00", "2026-09-15T19:00:00+08:00"),
                    ORGANIZER));
    assertEquals(40903, code(e));
  }

  @Test
  void updateRejectsConflictingSlot() {
    when(meetingMapper.selectOne(any())).thenReturn(futureMeeting());
    when(roomMapper.selectOne(any())).thenReturn(enabledRoom());
    when(meetingMapper.selectCount(any())).thenReturn(1L); // another ACTIVE meeting overlaps
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                service.update(
                    10L,
                    updateRequest(1L, "2026-09-15T18:00:00+08:00", "2026-09-15T19:00:00+08:00"),
                    ORGANIZER));
    assertEquals(40902, code(e));
  }

  @Test
  void updateConflictQueryExcludesSelf() {
    Meeting m = futureMeeting();
    when(meetingMapper.selectOne(any())).thenReturn(m);
    when(roomMapper.selectOne(any())).thenReturn(enabledRoom());
    when(meetingMapper.selectCount(any())).thenReturn(0L);
    stubVoLookups();

    service.update(
        10L,
        updateRequest(1L, "2026-09-15T18:00:00+08:00", "2026-09-15T19:00:00+08:00"),
        ORGANIZER);

    // The overlap query must exclude the meeting itself (id <> 10), otherwise keeping the
    // same slot would always look like a self-conflict.
    verify(meetingMapper)
        .selectCount(
            argThat(
                w -> {
                  String segment = w.getSqlSegment();
                  return segment.contains("id <>") || segment.contains("id !=");
                }));
  }

  @Test
  void updateNotStartedClearsNullDescriptionExplicitly() {
    Meeting m = futureMeeting();
    m.setDescription("旧说明");
    when(meetingMapper.selectOne(any())).thenReturn(m);
    when(roomMapper.selectOne(any())).thenReturn(enabledRoom());
    when(meetingMapper.selectCount(any())).thenReturn(0L);
    stubVoLookups();

    // PUT full-update semantics: an omitted (null) description must clear the column.
    com.roomflow.domain.meeting.UpdateMeetingRequest r =
        updateRequest(1L, "2026-09-15T18:00:00+08:00", "2026-09-15T19:00:00+08:00");
    r.setDescription(null);

    MeetingVO vo = service.update(10L, r, ORGANIZER);

    assertNull(m.getDescription());
    assertNull(vo.getDescription());
    // Regression guard: updateById's NOT_NULL strategy would silently drop description from
    // the SET clause and keep the old value; the explicit SET must contain it, bound to null.
    verify(meetingMapper)
        .update(
            isNull(),
            argThat(
                w ->
                    w
                            instanceof
                            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                                        Meeting>
                                    uw
                        && uw.getSqlSet().contains("description=")
                        && uw.getParamNameValuePairs().containsValue(null)));
  }

  @Test
  void updateStartedClearsNullDescriptionExplicitly() {
    Meeting m = runningMeeting();
    m.setDescription("旧说明");
    when(meetingMapper.selectOne(any())).thenReturn(m);
    stubVoLookups();

    com.roomflow.domain.meeting.UpdateMeetingRequest r =
        updateRequest(1L, "2026-09-15T15:00:00+08:00", "2026-09-15T17:00:00+08:00");
    r.setDescription(null);

    MeetingVO vo = service.update(10L, r, ORGANIZER);

    assertNull(m.getDescription());
    assertNull(vo.getDescription());
    verify(meetingMapper)
        .update(
            isNull(),
            argThat(
                w ->
                    w
                            instanceof
                            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                                        Meeting>
                                    uw
                        && uw.getSqlSet().contains("description=")
                        && uw.getParamNameValuePairs().containsValue(null)));
    verify(roomMapper, never()).selectOne(any());
  }

  @Test
  void updateAllowsRoomChangeWhenActiveCountEqualsCapacity() {
    Meeting m = futureMeeting();
    when(meetingMapper.selectOne(any())).thenReturn(m);
    Room exact = enabledRoom();
    exact.setId(2L);
    exact.setCapacity(2);
    when(roomMapper.selectOne(any())).thenReturn(exact);
    when(meetingMapper.selectCount(any())).thenReturn(0L); // no schedule conflict
    // Boundary: activeCount == capacity must pass — only strictly greater is 40903.
    when(participantMapper.selectCount(any())).thenReturn(2L);
    when(roomMapper.selectById(2L)).thenReturn(exact);
    when(accountMapper.selectById(1L)).thenReturn(account(1L, "alice"));

    MeetingVO vo =
        service.update(
            10L,
            updateRequest(2L, "2026-09-15T18:00:00+08:00", "2026-09-15T19:00:00+08:00"),
            ORGANIZER);

    assertEquals(2L, vo.getRoomId());
    verify(meetingMapper).update(isNull(), any());
  }

  // ---- cancel ----

  @Test
  void cancelSucceedsForOrganizer() {
    Meeting m = futureMeeting();
    when(meetingMapper.selectOne(any())).thenReturn(m);
    stubVoLookups();

    MeetingVO vo = service.cancel(10L, ORGANIZER);

    assertEquals(MeetingStatus.CANCELLED, m.getStatus());
    assertEquals(MeetingStatus.CANCELLED, vo.getStatus());
    verify(meetingMapper).updateById(m);
    // Cancellation sends no notification per contract.
    verify(notificationProducer, never()).send(any());
  }

  @Test
  void cancelAllowedByAdmin() {
    Meeting m = futureMeeting();
    when(meetingMapper.selectOne(any())).thenReturn(m);
    stubVoLookups();
    service.cancel(10L, ADMIN);
    assertEquals(MeetingStatus.CANCELLED, m.getStatus());
  }

  @Test
  void cancelRejectsNonPrivilegedCaller() {
    when(meetingMapper.selectOne(any())).thenReturn(futureMeeting());
    BizException e = assertThrows(BizException.class, () -> service.cancel(10L, OTHER));
    assertEquals(40301, code(e));
  }

  @Test
  void cancelRejectsDeletedMissingEndedCancelled() {
    when(meetingMapper.selectOne(any())).thenReturn(null);
    assertEquals(
        40401, code(assertThrows(BizException.class, () -> service.cancel(10L, ORGANIZER))));

    when(meetingMapper.selectOne(any()))
        .thenReturn(meeting(MeetingStatus.DELETED, NOW_LOCAL.plusHours(1), NOW_LOCAL.plusHours(2)));
    assertEquals(
        40401, code(assertThrows(BizException.class, () -> service.cancel(10L, ORGANIZER))));

    when(meetingMapper.selectOne(any()))
        .thenReturn(meeting(MeetingStatus.ENDED, NOW_LOCAL.plusHours(1), NOW_LOCAL.plusHours(2)));
    assertEquals(
        40909, code(assertThrows(BizException.class, () -> service.cancel(10L, ORGANIZER))));

    when(meetingMapper.selectOne(any()))
        .thenReturn(
            meeting(MeetingStatus.CANCELLED, NOW_LOCAL.plusHours(1), NOW_LOCAL.plusHours(2)));
    assertEquals(
        40909, code(assertThrows(BizException.class, () -> service.cancel(10L, ORGANIZER))));
  }

  @Test
  void cancelRejectsStartedOrStartingNowMeeting() {
    // startTime == now is "started" -> only end-early remains.
    when(meetingMapper.selectOne(any()))
        .thenReturn(meeting(MeetingStatus.ACTIVE, NOW_LOCAL, NOW_LOCAL.plusHours(1)));
    assertEquals(
        40909, code(assertThrows(BizException.class, () -> service.cancel(10L, ORGANIZER))));

    when(meetingMapper.selectOne(any())).thenReturn(runningMeeting());
    assertEquals(
        40909, code(assertThrows(BizException.class, () -> service.cancel(10L, ORGANIZER))));
  }

  // ---- delete ----

  @Test
  void deleteSucceedsForAnyNonDeletedStatus() {
    for (MeetingStatus status :
        new MeetingStatus[] {MeetingStatus.ACTIVE, MeetingStatus.ENDED, MeetingStatus.CANCELLED}) {
      Meeting m = meeting(status, NOW_LOCAL.plusHours(1), NOW_LOCAL.plusHours(2));
      when(meetingMapper.selectOne(any())).thenReturn(m);
      service.delete(10L, ORGANIZER);
      assertEquals(MeetingStatus.DELETED, m.getStatus());
    }
    // updateById was invoked once per delete call (equal-matching verifies would collide).
    verify(meetingMapper, times(3)).updateById(any(Meeting.class));
    // Deletion sends no notification per contract.
    verify(notificationProducer, never()).send(any());
  }

  @Test
  void deleteAllowedByAdminOnOthersMeeting() {
    Meeting m = futureMeeting();
    when(meetingMapper.selectOne(any())).thenReturn(m);
    service.delete(10L, ADMIN);
    assertEquals(MeetingStatus.DELETED, m.getStatus());
  }

  @Test
  void deleteRejectsNonPrivilegedCaller() {
    when(meetingMapper.selectOne(any())).thenReturn(futureMeeting());
    BizException e = assertThrows(BizException.class, () -> service.delete(10L, OTHER));
    assertEquals(40301, code(e));
  }

  @Test
  void deleteRejectsMissingOrAlreadyDeleted() {
    when(meetingMapper.selectOne(any())).thenReturn(null);
    assertEquals(
        40401, code(assertThrows(BizException.class, () -> service.delete(10L, ORGANIZER))));

    when(meetingMapper.selectOne(any()))
        .thenReturn(meeting(MeetingStatus.DELETED, NOW_LOCAL.plusHours(1), NOW_LOCAL.plusHours(2)));
    assertEquals(
        40401, code(assertThrows(BizException.class, () -> service.delete(10L, ORGANIZER))));
  }

  // ---- end-early ----

  private static Participant activeParticipant(Long accountId) {
    Participant p = new Participant();
    p.setMeetingId(10L);
    p.setAccountId(accountId);
    p.setIsOrganizer(false);
    p.setBanned(false);
    return p; // leftAt null = active
  }

  @Test
  void endEarlySucceedsAndNotifiesAllActiveParticipants() {
    Meeting m = runningMeeting();
    when(meetingMapper.selectOne(any())).thenReturn(m);
    when(participantMapper.selectList(any()))
        .thenReturn(List.of(activeParticipant(1L), activeParticipant(2L)));
    stubVoLookups();

    MeetingVO vo = service.endEarly(10L, ORGANIZER);

    assertEquals(MeetingStatus.ENDED, m.getStatus());
    assertEquals(true, m.getEndedEarly());
    assertEquals(MeetingStatus.ENDED, vo.getStatus());
    assertEquals(true, vo.getEndedEarly());
    verify(meetingMapper).updateById(m);
    verify(notificationProducer)
        .send(
            argThat(
                (NotificationMessage msg) ->
                    msg.type() == NotificationType.MEETING_ENDED
                        && msg.accountId().equals(1L)
                        && "meeting-ended:10:1".equals(msg.messageId())));
    verify(notificationProducer)
        .send(
            argThat(
                (NotificationMessage msg) ->
                    msg.type() == NotificationType.MEETING_ENDED
                        && msg.accountId().equals(2L)
                        && "meeting-ended:10:2".equals(msg.messageId())));
  }

  @Test
  void endEarlyAllowedByAdmin() {
    Meeting m = runningMeeting();
    when(meetingMapper.selectOne(any())).thenReturn(m);
    when(participantMapper.selectList(any())).thenReturn(List.of(activeParticipant(1L)));
    stubVoLookups();
    service.endEarly(10L, ADMIN);
    assertEquals(MeetingStatus.ENDED, m.getStatus());
    assertEquals(true, m.getEndedEarly());
  }

  @Test
  void endEarlyRejectsNonPrivilegedCaller() {
    when(meetingMapper.selectOne(any())).thenReturn(runningMeeting());
    BizException e = assertThrows(BizException.class, () -> service.endEarly(10L, OTHER));
    assertEquals(40301, code(e));
  }

  @Test
  void endEarlyRejectsDeletedEndedCancelled() {
    when(meetingMapper.selectOne(any()))
        .thenReturn(
            meeting(MeetingStatus.DELETED, NOW_LOCAL.minusHours(1), NOW_LOCAL.plusHours(1)));
    assertEquals(
        40401, code(assertThrows(BizException.class, () -> service.endEarly(10L, ORGANIZER))));

    when(meetingMapper.selectOne(any()))
        .thenReturn(meeting(MeetingStatus.ENDED, NOW_LOCAL.minusHours(1), NOW_LOCAL.plusHours(1)));
    assertEquals(
        40909, code(assertThrows(BizException.class, () -> service.endEarly(10L, ORGANIZER))));

    when(meetingMapper.selectOne(any()))
        .thenReturn(
            meeting(MeetingStatus.CANCELLED, NOW_LOCAL.minusHours(1), NOW_LOCAL.plusHours(1)));
    assertEquals(
        40909, code(assertThrows(BizException.class, () -> service.endEarly(10L, ORGANIZER))));
  }

  @Test
  void endEarlyRejectsNotYetStarted() {
    when(meetingMapper.selectOne(any())).thenReturn(futureMeeting());
    BizException e = assertThrows(BizException.class, () -> service.endEarly(10L, ORGANIZER));
    assertEquals(40909, code(e));
  }

  @Test
  void endEarlyRejectsAtEndBoundary() {
    // now == endTime belongs to the scheduler sweep, not the manual endpoint.
    when(meetingMapper.selectOne(any()))
        .thenReturn(meeting(MeetingStatus.ACTIVE, NOW_LOCAL.minusHours(1), NOW_LOCAL));
    BizException e = assertThrows(BizException.class, () -> service.endEarly(10L, ORGANIZER));
    assertEquals(40909, code(e));
  }

  @Test
  void endEarlyPublishesNotificationsOnlyAfterCommit() {
    Meeting m = runningMeeting();
    when(meetingMapper.selectOne(any())).thenReturn(m);
    when(participantMapper.selectList(any())).thenReturn(List.of(activeParticipant(1L)));
    stubVoLookups();

    // Inside a transaction the MEETING_ENDED publish must defer to afterCommit.
    org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
    try {
      service.endEarly(10L, ORGANIZER);
      verify(notificationProducer, never()).send(any());
      for (org.springframework.transaction.support.TransactionSynchronization sync :
          org.springframework.transaction.support.TransactionSynchronizationManager
              .getSynchronizations()) {
        sync.afterCommit();
      }
      verify(notificationProducer)
          .send(argThat((NotificationMessage msg) -> msg.type() == NotificationType.MEETING_ENDED));
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .clearSynchronization();
    }
  }

  @Test
  void endEarlyStillSucceedsWhenNotificationPublishFails() {
    Meeting m = runningMeeting();
    when(meetingMapper.selectOne(any())).thenReturn(m);
    when(participantMapper.selectList(any())).thenReturn(List.of(activeParticipant(1L)));
    stubVoLookups();
    org.mockito.Mockito.doThrow(new RuntimeException("amqp down"))
        .when(notificationProducer)
        .send(any());

    MeetingVO vo = service.endEarly(10L, ORGANIZER);
    assertEquals(MeetingStatus.ENDED, vo.getStatus());
  }

  // ---- scheduled sweep ----

  @Test
  void endMeetingIfActiveEndsOverdueAndNotifies() {
    Meeting overdue =
        meeting(MeetingStatus.ACTIVE, NOW_LOCAL.minusHours(2), NOW_LOCAL.minusHours(1));
    when(meetingMapper.update(isNull(), any())).thenReturn(1);
    when(meetingMapper.selectById(10L)).thenReturn(overdue);
    when(participantMapper.selectList(any())).thenReturn(List.of(activeParticipant(1L)));

    boolean ended = service.endMeetingIfActive(10L);

    assertTrue(ended);
    // The transition is a conditional UPDATE guarded on status=ACTIVE; updateFill does not run
    // for update(null, wrapper), so updated_at must be refreshed in the SET clause.
    verify(meetingMapper)
        .update(
            isNull(),
            argThat(
                w ->
                    w
                            instanceof
                            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                                        Meeting>
                                    uw
                        && w.getSqlSegment().contains("status")
                        && w.getSqlSegment().contains("end_time")
                        && uw.getSqlSet().contains("status=")
                        && uw.getSqlSet().contains("updated_at=")));
    verify(notificationProducer)
        .send(
            argThat(
                (NotificationMessage msg) ->
                    msg.type() == NotificationType.MEETING_ENDED
                        && "meeting-ended:10:1".equals(msg.messageId())));
  }

  @Test
  void endMeetingIfActiveIsIdempotentWhenAlreadyTransitioned() {
    // A concurrent end-early/cancel/delete or a previous sweep already moved the row off ACTIVE.
    when(meetingMapper.update(isNull(), any())).thenReturn(0);

    boolean ended = service.endMeetingIfActive(10L);

    assertEquals(false, ended);
    verify(notificationProducer, never()).send(any());
  }

  @Test
  void findOverdueActiveMeetingIdsReturnsIds() {
    Meeting a = new Meeting();
    a.setId(11L);
    Meeting b = new Meeting();
    b.setId(12L);
    when(meetingMapper.selectList(any())).thenReturn(List.of(a, b));

    assertEquals(List.of(11L, 12L), service.findOverdueActiveMeetingIds(NOW_LOCAL));
    verify(meetingMapper)
        .selectList(
            argThat(
                w ->
                    w.getSqlSegment().contains("status")
                        && w.getSqlSegment().contains("end_time")));
  }
}
