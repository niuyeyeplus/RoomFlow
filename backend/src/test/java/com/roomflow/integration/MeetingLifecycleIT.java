package com.roomflow.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roomflow.common.enums.MeetingStatus;
import com.roomflow.common.enums.NotificationType;
import com.roomflow.common.enums.Role;
import com.roomflow.common.exception.BizException;
import com.roomflow.domain.account.Account;
import com.roomflow.domain.meeting.CreateMeetingRequest;
import com.roomflow.domain.meeting.Meeting;
import com.roomflow.domain.meeting.MeetingVO;
import com.roomflow.domain.notification.Notification;
import com.roomflow.domain.participant.Participant;
import com.roomflow.domain.room.Room;
import com.roomflow.mapper.AccountMapper;
import com.roomflow.mapper.MeetingMapper;
import com.roomflow.mapper.NotificationMapper;
import com.roomflow.mapper.ParticipantMapper;
import com.roomflow.mapper.RoomMapper;
import com.roomflow.security.LoginAccount;
import com.roomflow.service.MeetingService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Meeting-lifecycle integration test on real MySQL/Redis/RabbitMQ containers: manual end-early,
 * cancel/delete visibility, the scheduled auto-end sweep (short interval), end notifications over
 * the real MQ+consumer chain, and the ShedLock Redis lock actually engaging.
 *
 * <p>Bound to failsafe (*IT): only runs under `mvnw verify` with Docker; `mvnw test` stays
 * Docker-free.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(
    properties = {
      // Re-enable the sweep that AbstractContainersIT disables for all other ITs.
      "roomflow.scheduler.enabled=true",
      "roomflow.scheduler.interval=500ms",
      "roomflow.scheduler.lock-at-least=PT2S",
      "roomflow.scheduler.lock-at-most=PT10S"
    })
class MeetingLifecycleIT extends AbstractContainersIT {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final ZoneOffset BEIJING = ZoneOffset.of("+08:00");
  private static final long AWAIT_TIMEOUT_MS = 30_000;

  @Autowired private MeetingService meetingService;
  @Autowired private MeetingMapper meetingMapper;
  @Autowired private ParticipantMapper participantMapper;
  @Autowired private NotificationMapper notificationMapper;
  @Autowired private AccountMapper accountMapper;
  @Autowired private RoomMapper roomMapper;
  @Autowired private StringRedisTemplate redis;

  private LoginAccount admin() {
    Account admin =
        accountMapper.selectOne(
            new LambdaQueryWrapper<Account>().eq(Account::getUsername, "admin"));
    return new LoginAccount(admin.getId(), admin.getUsername(), Role.ADMIN);
  }

  /**
   * Inserts a dedicated enabled room for a single test and returns its id. All ITs share one
   * Testcontainers database, so picking the Nth enabled seed room both collides on time slots
   * (leftover ACTIVE meetings from other ITs) and shifts index when other tests disable rooms. A
   * fresh room per test makes cross-test slot overlap impossible, so nextSlot() reuse is safe.
   */
  private Long newRoom() {
    Room room = new Room();
    room.setName("IT-lifecycle-room");
    room.setLocation("IT专用");
    room.setCapacity(10);
    room.setEnabled(true);
    roomMapper.insert(room);
    return room.getId();
  }

  private Account newUser(String username) {
    Account account = new Account();
    account.setUsername(username);
    account.setPasswordHash("$2a$10$dummyhashforitonly0000000000000000000000000000");
    account.setRole(Role.USER);
    account.setStatus(1);
    accountMapper.insert(account);
    return account;
  }

  private Participant addParticipant(Long meetingId, Long accountId, boolean organizer) {
    Participant p = new Participant();
    p.setMeetingId(meetingId);
    p.setAccountId(accountId);
    p.setIsOrganizer(organizer);
    p.setBanned(false);
    participantMapper.insert(p);
    return p;
  }

  /**
   * Inserts an ACTIVE meeting directly, bypassing the create-time rules (for past/started slots).
   *
   * <p>Times are truncated to whole seconds because {@code meeting.start_time/end_time} are
   * DATETIME(fsp=0): MySQL rounds fractional seconds on insert, so a nanosecond-precision value
   * could land up to ~1s later than intended and flip boundary comparisons against the execution
   * clock. Truncating here keeps the in-memory value identical to the stored one.
   */
  private Meeting insertMeeting(
      Long roomId, Long organizerId, LocalDateTime start, LocalDateTime end) {
    Meeting m = new Meeting();
    m.setTitle("生命周期IT会议");
    m.setRoomId(roomId);
    m.setOrganizerId(organizerId);
    m.setStartTime(start.truncatedTo(ChronoUnit.SECONDS));
    m.setEndTime(end.truncatedTo(ChronoUnit.SECONDS));
    m.setStatus(MeetingStatus.ACTIVE);
    m.setEndedEarly(false);
    meetingMapper.insert(m);
    return m;
  }

  private static OffsetDateTime nextSlot() {
    long minutes = Instant.now().getEpochSecond() / 60;
    long startMin = (minutes / 15 + 1) * 15;
    return OffsetDateTime.ofInstant(Instant.ofEpochSecond(startMin * 60), BEIJING);
  }

  private static CreateMeetingRequest createRequest(
      Long roomId, OffsetDateTime start, OffsetDateTime end) {
    CreateMeetingRequest r = new CreateMeetingRequest();
    r.setTitle("IT lifecycle create");
    r.setRoomId(roomId);
    r.setStartTime(start);
    r.setEndTime(end);
    return r;
  }

  private Meeting reload(Long id) {
    return meetingMapper.selectById(id);
  }

  private long endNotificationsFor(Long meetingId, Long accountId) {
    Long count =
        notificationMapper.selectCount(
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getMeetingId, meetingId)
                .eq(Notification::getAccountId, accountId)
                .eq(Notification::getType, NotificationType.MEETING_ENDED));
    return count == null ? 0 : count;
  }

  private static void awaitTrue(BooleanSupplier condition, String description)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(150);
    }
    throw new AssertionError("Timed out waiting for: " + description);
  }

  @Test
  void schedulerEndsOverdueMeetingAndNotifiesActiveParticipants() throws InterruptedException {
    LoginAccount admin = admin();
    Account user = newUser("it_sweep_user");
    LocalDateTime now = LocalDateTime.now(ZONE);
    Long roomId = newRoom();
    // Already past its end_time: the next sweep must end it.
    Meeting m = insertMeeting(roomId, admin.id(), now.minusHours(2), now.minusMinutes(30));
    addParticipant(m.getId(), admin.id(), true);
    addParticipant(m.getId(), user.getId(), false);
    Participant left = addParticipant(m.getId(), newUser("it_sweep_left").getId(), false);
    left.setLeftAt(now.minusMinutes(10));
    left.setLeaveReason(com.roomflow.common.enums.LeaveReason.USER_LEFT);
    participantMapper.updateById(left);

    awaitTrue(
        () -> reload(m.getId()).getStatus() == MeetingStatus.ENDED,
        "scheduler to end the overdue meeting");

    Meeting ended = reload(m.getId());
    assertEquals(MeetingStatus.ENDED, ended.getStatus());
    // Automatic end must not be flagged as early.
    assertFalse(Boolean.TRUE.equals(ended.getEndedEarly()));

    // Real RabbitMQ + consumer chain: one MEETING_ENDED per ACTIVE participant, none for the
    // user who left.
    awaitTrue(
        () -> endNotificationsFor(m.getId(), user.getId()) == 1,
        "end notification for active participant");
    assertEquals(1, endNotificationsFor(m.getId(), admin.id()));
    assertEquals(0, endNotificationsFor(m.getId(), left.getAccountId()));

    // Idempotency: further sweeps must not re-notify.
    Thread.sleep(1500);
    assertEquals(1, endNotificationsFor(m.getId(), user.getId()));
  }

  @Test
  void shedlockKeyIsHeldInRedisDuringSweep() throws InterruptedException {
    awaitTrue(
        () -> {
          Set<String> keys = redis.keys("*meeting-end-sweep*");
          return keys != null && !keys.isEmpty();
        },
        "ShedLock key for meeting-end-sweep in Redis");
  }

  @Test
  void endEarlyEndsMeetingReleasesSlotAndNotifies() throws InterruptedException {
    LoginAccount admin = admin();
    Account user = newUser("it_early_user");
    LocalDateTime now = LocalDateTime.now(ZONE);
    Long roomId = newRoom();
    // In-progress meeting [now-1h, now+2h]; its tail overlaps any future booking.
    Meeting m = insertMeeting(roomId, admin.id(), now.minusHours(1), now.plusHours(2));
    addParticipant(m.getId(), admin.id(), true);
    addParticipant(m.getId(), user.getId(), false);

    // While still ACTIVE the tail is occupied: an overlapping booking is rejected.
    OffsetDateTime start = nextSlot();
    BizException conflict =
        assertThrows(
            BizException.class,
            () -> meetingService.create(createRequest(roomId, start, start.plusHours(1)), admin));
    assertEquals(40902, conflict.getErrorCode().getCode());

    MeetingVO ended = meetingService.endEarly(m.getId(), admin);
    assertEquals(MeetingStatus.ENDED, ended.getStatus());
    assertEquals(Boolean.TRUE, ended.getEndedEarly());
    assertEquals(MeetingStatus.ENDED, reload(m.getId()).getStatus());

    // The released tail is bookable again on the same room.
    MeetingVO rebooked =
        meetingService.create(createRequest(roomId, start, start.plusHours(1)), admin);
    assertNotNull(rebooked.getId());

    // End notification reaches every active participant exactly once.
    awaitTrue(
        () -> endNotificationsFor(m.getId(), user.getId()) == 1,
        "end-early notification for participant");
    assertEquals(1, endNotificationsFor(m.getId(), admin.id()));
  }

  @Test
  void endEarlyRejectsBeforeStartAndAtEndBoundary() {
    LoginAccount admin = admin();
    LocalDateTime now = LocalDateTime.now(ZONE);
    Long roomId = newRoom();
    Meeting future = insertMeeting(roomId, admin.id(), now.plusHours(1), now.plusHours(2));
    BizException e =
        assertThrows(BizException.class, () -> meetingService.endEarly(future.getId(), admin));
    assertEquals(40909, e.getErrorCode().getCode());

    // now == endTime belongs to the scheduler. insertMeeting truncates to whole seconds, so the
    // stored end_time is <= the execution-time now and the 40909 guard must fire. (The 500ms
    // sweep may also flip the meeting to ENDED first — endEarly rejects that path with the same
    // 40909, so both outcomes converge on this assertion.)
    Meeting atEnd = insertMeeting(roomId, admin.id(), now.minusHours(1), now);
    e = assertThrows(BizException.class, () -> meetingService.endEarly(atEnd.getId(), admin));
    assertEquals(40909, e.getErrorCode().getCode());
  }

  @Test
  void cancelKeepsMeetingVisibleAndReleasesSlot() {
    LoginAccount admin = admin();
    Long roomId = newRoom();
    OffsetDateTime start = nextSlot().plusHours(3);
    MeetingVO created =
        meetingService.create(createRequest(roomId, start, start.plusHours(1)), admin);

    BizException conflict =
        assertThrows(
            BizException.class,
            () -> meetingService.create(createRequest(roomId, start, start.plusHours(1)), admin));
    assertEquals(40902, conflict.getErrorCode().getCode());

    MeetingVO cancelled = meetingService.cancel(created.getId(), admin);
    assertEquals(MeetingStatus.CANCELLED, cancelled.getStatus());

    // Cancelled meetings stay visible (share link shows cancelled state) but free the slot.
    assertNotNull(meetingService.getDetail(created.getId()));
    assertNotNull(
        meetingService.create(createRequest(roomId, start, start.plusHours(1)), admin).getId());
  }

  @Test
  void deleteHidesMeetingButKeepsRows() {
    LoginAccount admin = admin();
    Account user = newUser("it_delete_user");
    Long roomId = newRoom();
    OffsetDateTime start = nextSlot().plusHours(5);
    MeetingVO created =
        meetingService.create(createRequest(roomId, start, start.plusHours(1)), admin);
    addParticipant(created.getId(), user.getId(), false);

    meetingService.delete(created.getId(), admin);

    // Invisible to everyone, including the organizer.
    BizException e =
        assertThrows(BizException.class, () -> meetingService.getDetail(created.getId()));
    assertEquals(40401, e.getErrorCode().getCode());
    // Physical row and participant history preserved.
    assertEquals(MeetingStatus.DELETED, reload(created.getId()).getStatus());
    Long participantRows =
        participantMapper.selectCount(
            new LambdaQueryWrapper<Participant>().eq(Participant::getMeetingId, created.getId()));
    assertEquals(2L, participantRows);
    // A second delete is a 404, not a 200.
    e = assertThrows(BizException.class, () -> meetingService.delete(created.getId(), admin));
    assertEquals(40401, e.getErrorCode().getCode());
  }

  @Test
  void updateReschedulesBeforeStartAndExcludesSelfFromConflict() {
    LoginAccount admin = admin();
    Long roomId = newRoom();
    OffsetDateTime start = nextSlot().plusHours(6);
    MeetingVO created =
        meetingService.create(createRequest(roomId, start, start.plusHours(1)), admin);

    // Moving to a different slot in the SAME room must not self-conflict.
    var request = new com.roomflow.domain.meeting.UpdateMeetingRequest();
    request.setTitle("生命周期IT会议（改）");
    request.setRoomId(roomId);
    request.setStartTime(start.plusHours(1));
    request.setEndTime(start.plusHours(2));
    MeetingVO updated = meetingService.update(created.getId(), request, admin);
    assertEquals("生命周期IT会议（改）", updated.getTitle());
    assertEquals(start.plusHours(1).toLocalDateTime(), updated.getStartTime().toLocalDateTime());

    // The new slot is now occupied again.
    BizException conflict =
        assertThrows(
            BizException.class,
            () ->
                meetingService.create(
                    createRequest(roomId, start.plusHours(1), start.plusHours(2)), admin));
    assertEquals(40902, conflict.getErrorCode().getCode());
  }

  @Test
  void updateClearsNullDescriptionToDbNull() {
    LoginAccount admin = admin();
    Long roomId = newRoom();
    OffsetDateTime start = nextSlot().plusHours(8);
    CreateMeetingRequest create = createRequest(roomId, start, start.plusHours(1));
    create.setDescription("初始说明");
    MeetingVO created = meetingService.create(create, admin);
    assertEquals("初始说明", created.getDescription());

    // PUT full-update semantics: an omitted (null) description must clear the column.
    // updateById's NOT_NULL strategy would silently keep the old value — regression guard.
    var request = new com.roomflow.domain.meeting.UpdateMeetingRequest();
    request.setTitle("清空说明的会议");
    request.setRoomId(roomId);
    request.setStartTime(start);
    request.setEndTime(start.plusHours(1));
    MeetingVO updated = meetingService.update(created.getId(), request, admin);

    assertNull(updated.getDescription());
    assertNull(meetingService.getDetail(created.getId()).getDescription());
    assertNull(reload(created.getId()).getDescription());
  }
}
