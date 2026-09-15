package com.roomflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roomflow.common.enums.LeaveReason;
import com.roomflow.common.enums.MeetingStatus;
import com.roomflow.common.enums.NotificationType;
import com.roomflow.common.enums.Role;
import com.roomflow.common.exception.BizException;
import com.roomflow.common.exception.ErrorCode;
import com.roomflow.domain.account.Account;
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
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Join/leave/kick state machine on the single (meeting, account) row: join -> insert or reuse a
 * USER_LEFT row; leave -> left_at+USER_LEFT (banned stays 0); kick -> banned=1 + KICKED, permanent
 * and never reset.
 */
@Service
public class ParticipantService {

  private static final Logger log = LoggerFactory.getLogger(ParticipantService.class);

  private final MeetingService meetingService;
  private final ParticipantMapper participantMapper;
  private final RoomMapper roomMapper;
  private final AccountMapper accountMapper;
  private final NotificationProducer notificationProducer;
  private final Clock clock;

  public ParticipantService(
      MeetingService meetingService,
      ParticipantMapper participantMapper,
      RoomMapper roomMapper,
      AccountMapper accountMapper,
      NotificationProducer notificationProducer,
      Clock clock) {
    this.meetingService = meetingService;
    this.participantMapper = participantMapper;
    this.roomMapper = roomMapper;
    this.accountMapper = accountMapper;
    this.notificationProducer = notificationProducer;
    this.clock = clock;
  }

  /**
   * Joins a meeting. Contract check order: meeting visible (40401) -> ACTIVE and not started
   * (40909) -> not banned (40904) -> no active record (40908) -> capacity (40903) -> insert or
   * reuse a USER_LEFT row (both return 200).
   */
  @Transactional(rollbackFor = Exception.class)
  public ParticipantVO join(Long meetingId, LoginAccount current) {
    // Locks the meeting row so the capacity check + insert serialize per meeting.
    Meeting meeting = meetingService.requireVisibleMeetingForUpdate(meetingId);
    requireJoinable(meeting);

    Participant record = findRecord(meetingId, current.id());
    if (record != null && Boolean.TRUE.equals(record.getBanned())) {
      throw new BizException(ErrorCode.JOIN_BANNED);
    }
    if (record != null && record.getLeftAt() == null) {
      throw new BizException(ErrorCode.DUPLICATE_JOIN);
    }

    Room room = roomMapper.selectById(meeting.getRoomId());
    int capacity = room == null || room.getCapacity() == null ? 0 : room.getCapacity();
    Long activeCount = countActive(meetingId);
    if (activeCount != null && activeCount >= capacity) {
      throw new BizException(ErrorCode.CAPACITY_FULL);
    }

    if (record == null) {
      record = new Participant();
      record.setMeetingId(meetingId);
      record.setAccountId(current.id());
      record.setIsOrganizer(false);
      record.setBanned(false);
      try {
        participantMapper.insert(record);
      } catch (DuplicateKeyException e) {
        // Unique-key race: a concurrent join created the row first.
        throw new BizException(ErrorCode.DUPLICATE_JOIN);
      }
    } else {
      // Reuse the USER_LEFT row: clear leftAt/leaveReason, banned stays 0, refresh joinedAt.
      record.setLeftAt(null);
      record.setLeaveReason(null);
      record.setJoinedAt(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
      participantMapper.updateById(record);
    }

    notifyOrganizer(
        meeting,
        NotificationType.PARTICIPANT_JOINED,
        "新参会者",
        "用户 " + current.username() + " 报名了你的会议「" + meeting.getTitle() + "」。");
    return ParticipantVO.from(record, current.username());
  }

  /**
   * Leaves a meeting: leftAt=now, leaveReason=USER_LEFT, banned stays 0. Only for non-organizer
   * active participants of an ACTIVE not-yet-started meeting. Rejoin afterwards is allowed.
   */
  @Transactional(rollbackFor = Exception.class)
  public void leave(Long meetingId, LoginAccount current) {
    Meeting meeting = meetingService.requireVisibleMeetingForUpdate(meetingId);
    requireJoinable(meeting);

    Participant record = findActiveRecord(meetingId, current.id());
    if (record == null) {
      throw new BizException(ErrorCode.NOT_FOUND);
    }
    if (Boolean.TRUE.equals(record.getIsOrganizer())) {
      // The organizer cannot leave their own meeting; cancel it instead.
      throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
    }
    record.setLeftAt(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
    record.setLeaveReason(LeaveReason.USER_LEFT);
    participantMapper.updateById(record);

    notifyOrganizer(
        meeting,
        NotificationType.PARTICIPANT_LEFT,
        "参会者退出",
        "用户 " + current.username() + " 退出了你的会议「" + meeting.getTitle() + "」。");
  }

  /**
   * Kicks a participant: banned=1, leaveReason=KICKED, leftAt=now; the ban is permanent. Only the
   * organizer or an ADMIN may kick; the organizer can never be kicked.
   */
  @Transactional(rollbackFor = Exception.class)
  public void kick(Long meetingId, Long targetAccountId, LoginAccount current) {
    Meeting meeting = meetingService.requireVisibleMeetingForUpdate(meetingId);
    boolean privileged =
        current.role() == Role.ADMIN || meeting.getOrganizerId().equals(current.id());
    if (!privileged) {
      throw new BizException(ErrorCode.FORBIDDEN);
    }
    if (meeting.getStatus() != MeetingStatus.ACTIVE) {
      throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
    }
    if (meeting.getOrganizerId().equals(targetAccountId)) {
      throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
    }
    Participant record = findActiveRecord(meetingId, targetAccountId);
    if (record == null) {
      // Covers "no record" and "already left/kicked" uniformly to avoid account enumeration.
      throw new BizException(ErrorCode.NOT_FOUND);
    }
    record.setBanned(true);
    record.setLeftAt(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
    record.setLeaveReason(LeaveReason.KICKED);
    participantMapper.updateById(record);

    publishAfterCommit(
        new NotificationMessage(
            null,
            targetAccountId,
            NotificationType.PARTICIPANT_KICKED,
            "你已被移出会议",
            "你已被移出会议「" + meeting.getTitle() + "」，无法再次报名。",
            meeting.getId()));
  }

  /** All participant records (left/kicked included), ordered by joinedAt asc, id asc. */
  public List<ParticipantVO> list(Long meetingId) {
    meetingService.requireVisibleMeeting(meetingId);
    List<Participant> records =
        participantMapper.selectList(
            new LambdaQueryWrapper<Participant>()
                .eq(Participant::getMeetingId, meetingId)
                .orderByAsc(Participant::getJoinedAt)
                .orderByAsc(Participant::getId));
    List<Long> accountIds = records.stream().map(Participant::getAccountId).distinct().toList();
    Map<Long, String> usernames =
        accountIds.isEmpty()
            ? Map.of()
            : accountMapper.selectByIds(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Account::getUsername));
    return records.stream()
        .map(p -> ParticipantVO.from(p, usernames.get(p.getAccountId())))
        .toList();
  }

  /** Joinable = status ACTIVE and startTime still in the future (joining stops once started). */
  private void requireJoinable(Meeting meeting) {
    if (meeting.getStatus() != MeetingStatus.ACTIVE
        || !meeting.getStartTime().isAfter(LocalDateTime.now(clock))) {
      throw new BizException(ErrorCode.STATE_NOT_ALLOWED);
    }
  }

  private Participant findRecord(Long meetingId, Long accountId) {
    return participantMapper.selectOne(
        new LambdaQueryWrapper<Participant>()
            .eq(Participant::getMeetingId, meetingId)
            .eq(Participant::getAccountId, accountId));
  }

  private Participant findActiveRecord(Long meetingId, Long accountId) {
    return participantMapper.selectOne(
        new LambdaQueryWrapper<Participant>()
            .eq(Participant::getMeetingId, meetingId)
            .eq(Participant::getAccountId, accountId)
            .isNull(Participant::getLeftAt));
  }

  private Long countActive(Long meetingId) {
    return participantMapper.selectCount(
        new LambdaQueryWrapper<Participant>()
            .eq(Participant::getMeetingId, meetingId)
            .isNull(Participant::getLeftAt));
  }

  private void notifyOrganizer(
      Meeting meeting, NotificationType type, String title, String content) {
    publishAfterCommit(
        new NotificationMessage(
            null, meeting.getOrganizerId(), type, title, content, meeting.getId()));
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
}
