package com.roomflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roomflow.common.enums.MeetingStatus;
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
import com.roomflow.domain.participant.Participant;
import com.roomflow.domain.participant.ParticipantVO;
import com.roomflow.domain.room.Room;
import com.roomflow.mapper.AccountMapper;
import com.roomflow.mapper.MeetingMapper;
import com.roomflow.mapper.ParticipantMapper;
import com.roomflow.mapper.RoomMapper;
import com.roomflow.security.LoginAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeetingService {

  private static final int MAX_PAGE_SIZE = 100;

  private final MeetingMapper meetingMapper;
  private final RoomMapper roomMapper;
  private final AccountMapper accountMapper;
  private final ParticipantMapper participantMapper;
  private final Clock clock;

  public MeetingService(
      MeetingMapper meetingMapper,
      RoomMapper roomMapper,
      AccountMapper accountMapper,
      ParticipantMapper participantMapper,
      Clock clock) {
    this.meetingMapper = meetingMapper;
    this.roomMapper = roomMapper;
    this.accountMapper = accountMapper;
    this.participantMapper = participantMapper;
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
