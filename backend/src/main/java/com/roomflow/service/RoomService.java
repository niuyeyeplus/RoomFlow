package com.roomflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roomflow.common.enums.MeetingStatus;
import com.roomflow.common.exception.BizException;
import com.roomflow.common.exception.ErrorCode;
import com.roomflow.common.result.ValidationErrors;
import com.roomflow.common.util.BeijingTime;
import com.roomflow.common.util.EquipmentCodec;
import com.roomflow.domain.meeting.Meeting;
import com.roomflow.domain.room.CreateRoomRequest;
import com.roomflow.domain.room.Room;
import com.roomflow.domain.room.RoomAvailabilityVO;
import com.roomflow.domain.room.RoomStatusRequest;
import com.roomflow.domain.room.RoomVO;
import com.roomflow.domain.room.TimeSlot;
import com.roomflow.domain.room.UpdateRoomRequest;
import com.roomflow.mapper.MeetingMapper;
import com.roomflow.mapper.RoomMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {

  /** Availability queries span at most 7 Beijing calendar days (inclusive). */
  private static final int MAX_AVAILABILITY_DAYS = 7;

  private final RoomMapper roomMapper;
  private final MeetingMapper meetingMapper;
  private final Clock clock;

  public RoomService(RoomMapper roomMapper, MeetingMapper meetingMapper, Clock clock) {
    this.roomMapper = roomMapper;
    this.meetingMapper = meetingMapper;
    this.clock = clock;
  }

  /** All rooms ordered by id asc (including disabled); optional enabled filter. */
  public List<RoomVO> list(Boolean enabled) {
    LambdaQueryWrapper<Room> query = new LambdaQueryWrapper<Room>().orderByAsc(Room::getId);
    if (enabled != null) {
      query.eq(Room::getEnabled, enabled);
    }
    return roomMapper.selectList(query).stream().map(RoomVO::from).toList();
  }

  public RoomVO get(Long id) {
    return RoomVO.from(requireRoom(id));
  }

  @Transactional(rollbackFor = Exception.class)
  public RoomVO create(CreateRoomRequest request) {
    Room room = new Room();
    room.setName(request.getName());
    room.setLocation(request.getLocation());
    room.setCapacity(request.getCapacity());
    room.setEquipment(EquipmentCodec.encode(request.getEquipment()));
    room.setEnabled(true);
    roomMapper.insert(room);
    return RoomVO.from(room);
  }

  /** PUT full update; never touches enabled (use the status endpoint). */
  @Transactional(rollbackFor = Exception.class)
  public RoomVO update(Long id, UpdateRoomRequest request) {
    Room room = requireRoom(id);
    room.setName(request.getName());
    room.setLocation(request.getLocation());
    room.setCapacity(request.getCapacity());
    room.setEquipment(EquipmentCodec.encode(request.getEquipment()));
    roomMapper.updateById(room);
    return RoomVO.from(room);
  }

  @Transactional(rollbackFor = Exception.class)
  public RoomVO updateStatus(Long id, RoomStatusRequest request) {
    Room room = requireRoom(id);
    if (Boolean.FALSE.equals(request.getEnabled())) {
      ensureNoUnfinishedMeetings(id);
    }
    room.setEnabled(request.getEnabled());
    roomMapper.updateById(room);
    return RoomVO.from(room);
  }

  /**
   * Soft delete = set enabled=false. Rejects when the room still has unfinished ACTIVE meetings
   * (40907). Idempotent: disabling an already-disabled room returns success.
   */
  @Transactional(rollbackFor = Exception.class)
  public void delete(Long id) {
    Room room = requireRoom(id);
    if (Boolean.FALSE.equals(room.getEnabled())) {
      return;
    }
    ensureNoUnfinishedMeetings(id);
    room.setEnabled(false);
    roomMapper.updateById(room);
  }

  /**
   * Returns the occupied [start,end) slots of ACTIVE meetings overlapping the given Beijing
   * calendar-day range. Free slots are derived on the frontend (range minus occupied).
   */
  public RoomAvailabilityVO availability(Long roomId, LocalDate startDate, LocalDate endDate) {
    Room room = requireRoom(roomId);
    // startDate is a required request parameter; missing values are rejected upstream with 40002.
    LocalDate effectiveEnd = endDate == null ? startDate : endDate;
    if (effectiveEnd.isBefore(startDate)) {
      throw new BizException(
          ErrorCode.PARAM_INVALID,
          ErrorCode.PARAM_INVALID.getDefaultMessage(),
          ValidationErrors.of("endDate", "endDate 不得早于 startDate"));
    }
    if (effectiveEnd.isAfter(startDate.plusDays(MAX_AVAILABILITY_DAYS - 1L))) {
      throw new BizException(
          ErrorCode.PARAM_INVALID,
          ErrorCode.PARAM_INVALID.getDefaultMessage(),
          ValidationErrors.of("endDate", "查询跨度不得超过 7 天"));
    }
    LocalDateTime rangeStart = startDate.atStartOfDay();
    LocalDateTime rangeEnd = effectiveEnd.plusDays(1).atStartOfDay();
    List<Meeting> meetings =
        meetingMapper.selectList(
            new LambdaQueryWrapper<Meeting>()
                .eq(Meeting::getRoomId, roomId)
                .eq(Meeting::getStatus, MeetingStatus.ACTIVE)
                .lt(Meeting::getStartTime, rangeEnd)
                .gt(Meeting::getEndTime, rangeStart)
                .orderByAsc(Meeting::getStartTime));
    List<TimeSlot> slots =
        meetings.stream()
            .map(
                m -> {
                  TimeSlot slot = new TimeSlot();
                  slot.setMeetingId(m.getId());
                  slot.setStartTime(BeijingTime.toOffset(m.getStartTime()));
                  slot.setEndTime(BeijingTime.toOffset(m.getEndTime()));
                  return slot;
                })
            .toList();
    RoomAvailabilityVO vo = new RoomAvailabilityVO();
    vo.setRoomId(room.getId());
    vo.setStartDate(startDate);
    vo.setEndDate(effectiveEnd);
    vo.setOccupiedSlots(slots);
    return vo;
  }

  private Room requireRoom(Long id) {
    Room room = id == null ? null : roomMapper.selectById(id);
    if (room == null) {
      throw new BizException(ErrorCode.NOT_FOUND);
    }
    return room;
  }

  private void ensureNoUnfinishedMeetings(Long roomId) {
    Long count =
        meetingMapper.selectCount(
            new LambdaQueryWrapper<Meeting>()
                .eq(Meeting::getRoomId, roomId)
                .eq(Meeting::getStatus, MeetingStatus.ACTIVE)
                .gt(Meeting::getEndTime, LocalDateTime.now(clock)));
    if (count != null && count > 0) {
      throw new BizException(ErrorCode.ROOM_HAS_ACTIVE_MEETING);
    }
  }
}
