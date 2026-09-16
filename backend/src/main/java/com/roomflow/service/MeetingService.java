package com.roomflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roomflow.common.enums.MeetingStatus;
import com.roomflow.common.enums.NotificationType;
import com.roomflow.common.enums.Role;
import com.roomflow.common.exception.BizException;
import com.roomflow.common.exception.ErrorCode;
import com.roomflow.common.result.PageResult;
import com.roomflow.common.result.ValidationErrors;
import com.roomflow.common.util.BeijingTime;
import com.roomflow.common.util.TimeRules;
import com.roomflow.domain.account.Account;
import com.roomflow.domain.meeting.CreateMeetingRequest;
import com.roomflow.domain.meeting.Meeting;
import com.roomflow.domain.meeting.MeetingDetailVO;
import com.roomflow.domain.meeting.MeetingVO;
import com.roomflow.domain.meeting.UpdateMeetingRequest;
import com.roomflow.domain.notification.NotificationMessage;
import com.roomflow.domain.participant.Participant;
import com.roomflow.domain.participant.ParticipantVO;
import com.roomflow.domain.room.Room;
import com.roomflow.mapper.AccountMapper;
import com.roomflow.mapper.MeetingMapper;
import com.roomflow.mapper.ParticipantMapper;
import com.roomflow.mapper.RoomMapper;
import com.roomflow.mq.NotificationProducer;
import com.roomflow.security.LoginAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class MeetingService {

  private static final Logger log = LoggerFactory.getLogger(MeetingService.class);
  private static final int MAX_PAGE_SIZE = 100;
  private static final DateTimeFormatter NOTIFY_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final MeetingMapper meetingMapper;
  private final RoomMapper roomMapper;
  private final AccountMapper accountMapper;
  private final ParticipantMapper participantMapper;
  private final NotificationProducer notificationProducer;
  private final Clock clock;

  public MeetingService(
      MeetingMapper meetingMapper,
      RoomMapper roomMapper,
      AccountMapper accountMapper,
      ParticipantMapper participantMapper,
      NotificationProducer notificationProducer,
      Clock clock) {
    this.meetingMapper = meetingMapper;
    this.roomMapper = roomMapper;
    this.accountMapper = accountMapper;
    this.participantMapper = participantMapper;
    this.notificationProducer = notificationProducer;
    this.clock = clock;
  }

  /**
   * Paged list ordered by startTime DESC, id DESC. status=DELETED is always invisible; the DELETED
   * filter value is rejected with 40001.
   */
  public PageResult<MeetingVO> list(
      int page,
      int size,
      Long roomId,
      LocalDate date,
      MeetingStatus status,
      boolean onlyMine,
      LoginAccount current) {
    validatePage(page, size);
    if (status == MeetingStatus.DELETED) {
      throw new BizException(
          ErrorCode.PARAM_INVALID,
          ErrorCode.PARAM_INVALID.getDefaultMessage(),
          ValidationErrors.of("status", "status 不可筛选 DELETED"));
    }
    LambdaQueryWrapper<Meeting> query =
        new LambdaQueryWrapper<Meeting>().ne(Meeting::getStatus, MeetingStatus.DELETED);
    if (roomId != null) {
      query.eq(Meeting::getRoomId, roomId);
    }
    if (date != null) {
      LocalDateTime dayStart = date.atStartOfDay();
      LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
      query.lt(Meeting::getStartTime, dayEnd).gt(Meeting::getEndTime, dayStart);
    }
    if (status != null) {
      query.eq(Meeting::getStatus, status);
    }
    if (onlyMine) {
      Long uid = current.id();
      query.and(
          w ->
              w.eq(Meeting::getOrganizerId, uid)
                  .or()
                  .apply(
                      "id IN (SELECT meeting_id FROM participant"
                          + " WHERE account_id = {0} AND left_at IS NULL)",
                      uid));
    }
    query.orderByDesc(Meeting::getStartTime).orderByDesc(Meeting::getId);

    Page<Meeting> mpPage = meetingMapper.selectPage(new Page<>(page, size), query);
    List<Meeting> records = mpPage.getRecords();
    Map<Long, String> roomNames = roomNamesOf(records);
    Map<Long, String> usernames =
        usernamesOf(records.stream().map(Meeting::getOrganizerId).distinct().toList());
    Map<Long, Long> counts = activeParticipantCounts(records.stream().map(Meeting::getId).toList());
    List<MeetingVO> vos =
        records.stream()
            .map(
                m ->
                    MeetingVO.from(
                        m,
                        roomNames.get(m.getRoomId()),
                        usernames.get(m.getOrganizerId()),
                        counts.getOrDefault(m.getId(), 0L).intValue()))
            .toList();
    return new PageResult<>(vos, mpPage.getTotal(), page, size);
  }

  /** Detail includes ALL participant records (left/kicked included), joinedAt asc, id asc. */
  public MeetingDetailVO getDetail(Long id) {
    Meeting meeting = meetingMapper.selectById(id);
    if (meeting == null || meeting.getStatus() == MeetingStatus.DELETED) {
      throw new BizException(ErrorCode.NOT_FOUND);
    }
    List<Participant> participants =
        participantMapper.selectList(
            new LambdaQueryWrapper<Participant>()
                .eq(Participant::getMeetingId, id)
                .orderByAsc(Participant::getJoinedAt)
                .orderByAsc(Participant::getId));
    List<Long> accountIds =
        participants.stream().map(Participant::getAccountId).distinct().toList();
    List<Long> nameLookupIds = new java.util.ArrayList<>(accountIds);
    if (!nameLookupIds.contains(meeting.getOrganizerId())) {
      nameLookupIds.add(meeting.getOrganizerId());
    }
    Map<Long, String> usernames = usernamesOf(nameLookupIds);
    List<ParticipantVO> participantVOs =
        participants.stream()
            .map(p -> ParticipantVO.from(p, usernames.get(p.getAccountId())))
            .toList();
    Room room = roomMapper.selectById(meeting.getRoomId());
    long active = participants.stream().filter(p -> p.getLeftAt() == null).count();
    MeetingVO base =
        MeetingVO.from(
            meeting,
            room == null ? null : room.getName(),
            usernames.get(meeting.getOrganizerId()),
            (int) active);
    return MeetingDetailVO.from(base, participantVOs);
  }

  /**
   * Creates a meeting; the creator automatically becomes the organizer participant (occupies one
   * capacity slot). Validation order follows the contract: field format (40001) -> time rules
   * (40905) -> room exists (40401) / disabled (40906) -> [start,end) overlap (40902).
   */
  @Transactional(rollbackFor = Exception.class)
  public MeetingVO create(CreateMeetingRequest request, LoginAccount current) {
    TimeRules.requireBeijingOffset(request.getStartTime(), "startTime");
    TimeRules.requireBeijingOffset(request.getEndTime(), "endTime");
    TimeRules.validateMeetingWindow(
        request.getStartTime(), request.getEndTime(), Instant.now(clock));

    // FOR UPDATE locks the room row for the whole transaction so concurrent
    // creates on the same room serialize their [start,end) conflict checks.
    Room room =
        roomMapper.selectOne(
            new LambdaQueryWrapper<Room>().eq(Room::getId, request.getRoomId()).last("FOR UPDATE"));
    if (room == null) {
      throw new BizException(ErrorCode.NOT_FOUND);
    }
    if (Boolean.FALSE.equals(room.getEnabled())) {
      throw new BizException(ErrorCode.ROOM_DISABLED);
    }

    LocalDateTime start = BeijingTime.toLocal(request.getStartTime());
    LocalDateTime end = BeijingTime.toLocal(request.getEndTime());
    Long conflicts =
        meetingMapper.selectCount(
            new LambdaQueryWrapper<Meeting>()
                .eq(Meeting::getRoomId, room.getId())
                .eq(Meeting::getStatus, MeetingStatus.ACTIVE)
                .lt(Meeting::getStartTime, end)
                .gt(Meeting::getEndTime, start));
    if (conflicts != null && conflicts > 0) {
      throw new BizException(ErrorCode.TIME_CONFLICT);
    }

    Meeting meeting = new Meeting();
    meeting.setTitle(request.getTitle());
    meeting.setDescription(request.getDescription());
    meeting.setRoomId(room.getId());
    meeting.setOrganizerId(current.id());
    meeting.setStartTime(start);
    meeting.setEndTime(end);
    meeting.setStatus(MeetingStatus.ACTIVE);
    meeting.setEndedEarly(false);
    meetingMapper.insert(meeting);

    Participant organizer = new Participant();
    organizer.setMeetingId(meeting.getId());
    organizer.setAccountId(current.id());
    organizer.setIsOrganizer(true);
    organizer.setBanned(false);
    participantMapper.insert(organizer);

    return MeetingVO.from(meeting, room.getName(), current.username(), 1);
  }

  /**
   * PUT full update. Not-yet-started meetings may change every field (full time rules 40905, room
   * exists 40401 / enabled 40906, [start,end) conflict excluding self 40902, new-room capacity vs
   * active participants 40903 — in that order). Started meetings (startTime &lt;= now, including
   * expired-but-unswept ACTIVE rows) may only change title/description; any
   * roomId/startTime/endTime difference is rejected with 40909 and time/conflict checks are
   * skipped.
   */
  @Transactional(rollbackFor = Exception.class)
  public MeetingVO update(Long id, UpdateMeetingRequest request, LoginAccount current) {
    Meeting meeting = requireVisibleMeetingForUpdate(id);
    requireOrganizerOrAdmin(meeting, current);
    if (meeting.getStatus() != MeetingStatus.ACTIVE) {
      throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
    }
    // Wire-format check applies to both branches (40001); it is a format rule, not a 40905
    // time rule, so it is still enforced for started meetings.
    TimeRules.requireBeijingOffset(request.getStartTime(), "startTime");
    TimeRules.requireBeijingOffset(request.getEndTime(), "endTime");

    LocalDateTime now = LocalDateTime.now(clock);
    if (!meeting.getStartTime().isAfter(now)) {
      // Already started: only title/description are mutable; schedule fields must match exactly.
      if (!request.getRoomId().equals(meeting.getRoomId())
          || !BeijingTime.toLocal(request.getStartTime()).equals(meeting.getStartTime())
          || !BeijingTime.toLocal(request.getEndTime()).equals(meeting.getEndTime())) {
        throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
      }
      meeting.setTitle(request.getTitle());
      meeting.setDescription(request.getDescription());
      meeting.setUpdatedAt(now.truncatedTo(ChronoUnit.SECONDS));
      // Explicit SET via LambdaUpdateWrapper: updateById's default NOT_NULL field strategy would
      // silently skip a null description, but PUT full-update semantics require an omitted
      // description to clear the column. update(null, wrapper) also bypasses
      // MetaObjectHandler.updateFill, so updated_at is set manually (same second truncation).
      meetingMapper.update(
          null,
          new LambdaUpdateWrapper<Meeting>()
              .eq(Meeting::getId, meeting.getId())
              .set(Meeting::getTitle, meeting.getTitle())
              .set(Meeting::getDescription, meeting.getDescription())
              .set(Meeting::getUpdatedAt, meeting.getUpdatedAt()));
      return toVO(meeting);
    }

    // Not started: full update path.
    TimeRules.validateMeetingWindow(
        request.getStartTime(), request.getEndTime(), Instant.now(clock));

    // FOR UPDATE locks the (possibly new) room row so the conflict check below serializes
    // against concurrent create/update on the same room (TOCTOU).
    Room room =
        roomMapper.selectOne(
            new LambdaQueryWrapper<Room>().eq(Room::getId, request.getRoomId()).last("FOR UPDATE"));
    if (room == null) {
      throw new BizException(ErrorCode.NOT_FOUND);
    }
    boolean roomChanged = !room.getId().equals(meeting.getRoomId());
    if (roomChanged && Boolean.FALSE.equals(room.getEnabled())) {
      throw new BizException(ErrorCode.ROOM_DISABLED);
    }

    LocalDateTime start = BeijingTime.toLocal(request.getStartTime());
    LocalDateTime end = BeijingTime.toLocal(request.getEndTime());
    Long conflicts =
        meetingMapper.selectCount(
            new LambdaQueryWrapper<Meeting>()
                .eq(Meeting::getRoomId, room.getId())
                .eq(Meeting::getStatus, MeetingStatus.ACTIVE)
                // Exclude this meeting: its stored slot is being replaced.
                .ne(Meeting::getId, meeting.getId())
                .lt(Meeting::getStartTime, end)
                .gt(Meeting::getEndTime, start));
    if (conflicts != null && conflicts > 0) {
      throw new BizException(ErrorCode.TIME_CONFLICT);
    }

    if (roomChanged) {
      Long activeCount = countActiveParticipants(meeting.getId());
      int capacity = room.getCapacity() == null ? 0 : room.getCapacity();
      if (activeCount != null && activeCount > capacity) {
        throw new BizException(ErrorCode.CAPACITY_FULL);
      }
    }

    meeting.setTitle(request.getTitle());
    meeting.setDescription(request.getDescription());
    meeting.setRoomId(room.getId());
    meeting.setStartTime(start);
    meeting.setEndTime(end);
    meeting.setUpdatedAt(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
    // Same explicit-SET rationale as the started branch: a null description must reach the
    // column (PUT full update), and updated_at is written manually because updateFill does
    // not run for update(null, wrapper).
    meetingMapper.update(
        null,
        new LambdaUpdateWrapper<Meeting>()
            .eq(Meeting::getId, meeting.getId())
            .set(Meeting::getTitle, meeting.getTitle())
            .set(Meeting::getDescription, meeting.getDescription())
            .set(Meeting::getRoomId, meeting.getRoomId())
            .set(Meeting::getStartTime, meeting.getStartTime())
            .set(Meeting::getEndTime, meeting.getEndTime())
            .set(Meeting::getUpdatedAt, meeting.getUpdatedAt()));
    return toVO(meeting);
  }

  /**
   * Cancels a not-yet-started ACTIVE meeting: status -&gt; CANCELLED (visible but slot released,
   * since conflict detection only counts ACTIVE). No notification per contract.
   */
  @Transactional(rollbackFor = Exception.class)
  public MeetingVO cancel(Long id, LoginAccount current) {
    Meeting meeting = requireVisibleMeetingForUpdate(id);
    requireOrganizerOrAdmin(meeting, current);
    if (meeting.getStatus() != MeetingStatus.ACTIVE
        || !meeting.getStartTime().isAfter(LocalDateTime.now(clock))) {
      throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
    }
    meeting.setStatus(MeetingStatus.CANCELLED);
    meetingMapper.updateById(meeting);
    return toVO(meeting);
  }

  /**
   * Logical delete: status -&gt; DELETED (row kept; participant/notification history preserved).
   * Any non-DELETED status may be deleted; afterwards the meeting is invisible everywhere.
   */
  @Transactional(rollbackFor = Exception.class)
  public void delete(Long id, LoginAccount current) {
    Meeting meeting = requireVisibleMeetingForUpdate(id);
    requireOrganizerOrAdmin(meeting, current);
    meeting.setStatus(MeetingStatus.DELETED);
    meetingMapper.updateById(meeting);
  }

  /**
   * Ends an in-progress meeting early: status -&gt; ENDED with endedEarly=true, releases the
   * remaining slot and stops joins (status no longer ACTIVE). Requires startTime &lt;= now &lt;
   * endTime; once now == endTime the scheduler owns the transition (40909). Sends MEETING_ENDED to
   * all active participants after commit.
   */
  @Transactional(rollbackFor = Exception.class)
  public MeetingVO endEarly(Long id, LoginAccount current) {
    Meeting meeting = requireVisibleMeetingForUpdate(id);
    requireOrganizerOrAdmin(meeting, current);
    LocalDateTime now = LocalDateTime.now(clock);
    if (meeting.getStatus() != MeetingStatus.ACTIVE
        || meeting.getStartTime().isAfter(now)
        || !now.isBefore(meeting.getEndTime())) {
      throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
    }
    meeting.setStatus(MeetingStatus.ENDED);
    meeting.setEndedEarly(true);
    meetingMapper.updateById(meeting);
    notifyMeetingEnded(meeting, "会议「" + meeting.getTitle() + "」已提前结束。");
    return toVO(meeting);
  }

  /** Ids of ACTIVE meetings whose endTime has passed — the scheduler's sweep candidates. */
  public List<Long> findOverdueActiveMeetingIds(LocalDateTime now) {
    return meetingMapper
        .selectList(
            new LambdaQueryWrapper<Meeting>()
                .select(Meeting::getId)
                .eq(Meeting::getStatus, MeetingStatus.ACTIVE)
                .le(Meeting::getEndTime, now))
        .stream()
        .map(Meeting::getId)
        .toList();
  }

  /**
   * Atomically transitions one overdue meeting ACTIVE -&gt; ENDED (endedEarly stays false) via a
   * conditional UPDATE guarded on status='ACTIVE'. Returns true only for the caller that won the
   * transition, so racing end-early/delete/concurrent sweeps can never double-end or double-notify.
   * Each call runs in its own transaction (invoked through the proxy by the scheduler) so one
   * failing meeting cannot roll back the whole sweep.
   */
  @Transactional(rollbackFor = Exception.class)
  public boolean endMeetingIfActive(Long meetingId) {
    LocalDateTime now = LocalDateTime.now(clock);
    int updated =
        meetingMapper.update(
            null,
            new LambdaUpdateWrapper<Meeting>()
                .eq(Meeting::getId, meetingId)
                .eq(Meeting::getStatus, MeetingStatus.ACTIVE)
                .le(Meeting::getEndTime, now)
                .set(Meeting::getStatus, MeetingStatus.ENDED)
                // update(null, wrapper) bypasses MetaObjectHandler.updateFill, so refresh
                // updated_at here with the same second truncation the handler applies.
                .set(Meeting::getUpdatedAt, now.truncatedTo(ChronoUnit.SECONDS)));
    if (updated == 0) {
      return false;
    }
    Meeting meeting = meetingMapper.selectById(meetingId);
    notifyMeetingEnded(
        meeting,
        "会议「" + meeting.getTitle() + "」已于 " + meeting.getEndTime().format(NOTIFY_TIME) + " 结束。");
    return true;
  }

  /**
   * Sends MEETING_ENDED to every active participant (left_at IS NULL), organizer included. The
   * deterministic messageId doubles as the consumer-side idempotency key, so a redelivery or a
   * near-simultaneous second publish cannot create duplicate notifications.
   */
  private void notifyMeetingEnded(Meeting meeting, String content) {
    List<Participant> actives =
        participantMapper.selectList(
            new LambdaQueryWrapper<Participant>()
                .eq(Participant::getMeetingId, meeting.getId())
                .isNull(Participant::getLeftAt));
    for (Participant participant : actives) {
      publishAfterCommit(
          new NotificationMessage(
              "meeting-ended:" + meeting.getId() + ":" + participant.getAccountId(),
              participant.getAccountId(),
              NotificationType.MEETING_ENDED,
              "会议已结束",
              content,
              meeting.getId()));
    }
  }

  /**
   * Delays the MQ publish until the transaction commits, so a later commit failure cannot leave a
   * phantom notification and a rollback never publishes. Publish failures are caught and logged — a
   * lost notification must not turn a committed state change into a 500.
   */
  private void publishAfterCommit(NotificationMessage message) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              sendQuietly(message);
            }
          });
    } else {
      // No surrounding transaction (e.g. direct unit-test invocation): send immediately.
      sendQuietly(message);
    }
  }

  private void sendQuietly(NotificationMessage message) {
    try {
      notificationProducer.send(message);
    } catch (RuntimeException e) {
      log.error(
          "Failed to publish {} notification for meeting {}",
          message.type(),
          message.meetingId(),
          e);
    }
  }

  private void requireOrganizerOrAdmin(Meeting meeting, LoginAccount current) {
    if (current.role() != Role.ADMIN && !meeting.getOrganizerId().equals(current.id())) {
      throw new BizException(ErrorCode.FORBIDDEN);
    }
  }

  private Long countActiveParticipants(Long meetingId) {
    return participantMapper.selectCount(
        new LambdaQueryWrapper<Participant>()
            .eq(Participant::getMeetingId, meetingId)
            .isNull(Participant::getLeftAt));
  }

  private MeetingVO toVO(Meeting meeting) {
    Room room = meeting.getRoomId() == null ? null : roomMapper.selectById(meeting.getRoomId());
    Account organizer = accountMapper.selectById(meeting.getOrganizerId());
    Long activeCount = countActiveParticipants(meeting.getId());
    return MeetingVO.from(
        meeting,
        room == null ? null : room.getName(),
        organizer == null ? null : organizer.getUsername(),
        activeCount == null ? 0 : activeCount.intValue());
  }

  /** Counts active participants (left_at IS NULL) per meeting in one grouped query. */
  Map<Long, Long> activeParticipantCounts(List<Long> meetingIds) {
    if (meetingIds.isEmpty()) {
      return Collections.emptyMap();
    }
    QueryWrapper<Participant> query = new QueryWrapper<>();
    query
        .select("meeting_id AS meetingId", "COUNT(*) AS cnt")
        .in("meeting_id", meetingIds)
        .isNull("left_at")
        .groupBy("meeting_id");
    return participantMapper.selectMaps(query).stream()
        .collect(
            Collectors.toMap(
                row -> ((Number) row.get("meetingId")).longValue(),
                row -> ((Number) row.get("cnt")).longValue()));
  }

  private Map<Long, String> roomNamesOf(List<Meeting> meetings) {
    List<Long> roomIds = meetings.stream().map(Meeting::getRoomId).distinct().toList();
    if (roomIds.isEmpty()) {
      return Collections.emptyMap();
    }
    return roomMapper.selectByIds(roomIds).stream()
        .collect(Collectors.toMap(Room::getId, Room::getName));
  }

  private Map<Long, String> usernamesOf(List<Long> accountIds) {
    if (accountIds.isEmpty()) {
      return Collections.emptyMap();
    }
    return accountMapper.selectByIds(accountIds).stream()
        .collect(Collectors.toMap(Account::getId, Account::getUsername));
  }

  private static void validatePage(int page, int size) {
    if (page < 1) {
      throw new BizException(
          ErrorCode.PARAM_INVALID,
          ErrorCode.PARAM_INVALID.getDefaultMessage(),
          ValidationErrors.of("page", "page 必须 >= 1"));
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new BizException(
          ErrorCode.PARAM_INVALID,
          ErrorCode.PARAM_INVALID.getDefaultMessage(),
          ValidationErrors.of("size", "size 必须在 1-100 之间"));
    }
  }

  /** Exposed for tests/other services needing the meeting-or-40401 guard. */
  public Meeting requireVisibleMeeting(Long meetingId) {
    Meeting meeting = meetingId == null ? null : meetingMapper.selectById(meetingId);
    if (meeting == null || meeting.getStatus() == MeetingStatus.DELETED) {
      throw new BizException(ErrorCode.NOT_FOUND);
    }
    return meeting;
  }

  /**
   * Same guard as {@link #requireVisibleMeeting} but reads the meeting row with SELECT ... FOR
   * UPDATE. Mutating participant flows (join/leave/kick) must call this inside their transaction so
   * capacity checks and row writes serialize per meeting.
   */
  public Meeting requireVisibleMeetingForUpdate(Long meetingId) {
    Meeting meeting =
        meetingId == null
            ? null
            : meetingMapper.selectOne(
                new LambdaQueryWrapper<Meeting>().eq(Meeting::getId, meetingId).last("FOR UPDATE"));
    if (meeting == null || meeting.getStatus() == MeetingStatus.DELETED) {
      throw new BizException(ErrorCode.NOT_FOUND);
    }
    return meeting;
  }
}
