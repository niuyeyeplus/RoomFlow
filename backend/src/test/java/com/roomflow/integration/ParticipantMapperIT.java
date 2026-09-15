package com.roomflow.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roomflow.common.enums.LeaveReason;
import com.roomflow.common.enums.MeetingStatus;
import com.roomflow.common.enums.Role;
import com.roomflow.domain.account.Account;
import com.roomflow.domain.meeting.Meeting;
import com.roomflow.domain.participant.Participant;
import com.roomflow.mapper.AccountMapper;
import com.roomflow.mapper.MeetingMapper;
import com.roomflow.mapper.ParticipantMapper;
import com.roomflow.mapper.RoomMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Mapper CRUD + uk_meeting_account semantics on real MySQL. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Transactional
class ParticipantMapperIT extends AbstractContainersIT {

  @Autowired private AccountMapper accountMapper;
  @Autowired private RoomMapper roomMapper;
  @Autowired private MeetingMapper meetingMapper;
  @Autowired private ParticipantMapper participantMapper;

  private Long accountId(String username) {
    Account a = new Account();
    a.setUsername(username);
    a.setPasswordHash("$2a$10$0123456789012345678901234567890123456789012345678901");
    a.setRole(Role.USER);
    a.setStatus(1);
    accountMapper.insert(a);
    return a.getId();
  }

  private Long meetingId(Long organizerId) {
    Meeting m = new Meeting();
    m.setTitle("it 会议");
    m.setRoomId(1L); // seeded room
    m.setOrganizerId(organizerId);
    m.setStartTime(LocalDateTime.now().plusHours(1));
    m.setEndTime(LocalDateTime.now().plusHours(2));
    m.setStatus(MeetingStatus.ACTIVE);
    m.setEndedEarly(false);
    meetingMapper.insert(m);
    return m.getId();
  }

  @Test
  void crudAndAutoFill() {
    Long uid = accountId("it_user_a");
    Long mid = meetingId(uid);
    Participant p = new Participant();
    p.setMeetingId(mid);
    p.setAccountId(uid);
    p.setIsOrganizer(true);
    p.setBanned(false);
    participantMapper.insert(p);
    assertNotNull(p.getId());
    assertNotNull(p.getJoinedAt(), "joined_at must be auto-filled");

    Participant loaded = participantMapper.selectById(p.getId());
    assertEquals(true, loaded.getIsOrganizer());
    assertNull(loaded.getLeftAt());
  }

  @Test
  void uniqueMeetingAccountRejectsSecondRow() {
    Long uid = accountId("it_user_b");
    Long mid = meetingId(uid);
    Participant first = new Participant();
    first.setMeetingId(mid);
    first.setAccountId(uid);
    first.setIsOrganizer(false);
    first.setBanned(false);
    participantMapper.insert(first);

    Participant dup = new Participant();
    dup.setMeetingId(mid);
    dup.setAccountId(uid);
    dup.setIsOrganizer(false);
    dup.setBanned(false);
    assertThrows(DuplicateKeyException.class, () -> participantMapper.insert(dup));
  }

  @Test
  void leaveThenRejoinReusesRow() {
    Long uid = accountId("it_user_c");
    Long mid = meetingId(uid);
    Participant p = new Participant();
    p.setMeetingId(mid);
    p.setAccountId(uid);
    p.setIsOrganizer(false);
    p.setBanned(false);
    participantMapper.insert(p);

    p.setLeftAt(LocalDateTime.now());
    p.setLeaveReason(LeaveReason.USER_LEFT);
    participantMapper.updateById(p);

    // Rejoin: reuse the same row, no second insert.
    p.setLeftAt(null);
    p.setLeaveReason(null);
    participantMapper.updateById(p);

    Long count =
        participantMapper.selectCount(
            new LambdaQueryWrapper<Participant>()
                .eq(Participant::getMeetingId, mid)
                .eq(Participant::getAccountId, uid));
    assertEquals(1, count);
    Participant reloaded = participantMapper.selectById(p.getId());
    assertNull(reloaded.getLeftAt());
    assertNull(reloaded.getLeaveReason());
  }
}
