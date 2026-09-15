package com.roomflow.domain.participant;

import com.roomflow.common.enums.LeaveReason;
import com.roomflow.common.util.BeijingTime;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class ParticipantVO {

  private Long id;
  private Long meetingId;
  private Long accountId;
  private String username;
  private Boolean isOrganizer;
  private Boolean banned;
  private OffsetDateTime joinedAt;
  private OffsetDateTime leftAt;
  private LeaveReason leaveReason;

  public static ParticipantVO from(Participant participant, String username) {
    ParticipantVO vo = new ParticipantVO();
    vo.setId(participant.getId());
    vo.setMeetingId(participant.getMeetingId());
    vo.setAccountId(participant.getAccountId());
    vo.setUsername(username);
    vo.setIsOrganizer(participant.getIsOrganizer());
    vo.setBanned(participant.getBanned());
    vo.setJoinedAt(BeijingTime.toOffset(participant.getJoinedAt()));
    vo.setLeftAt(BeijingTime.toOffset(participant.getLeftAt()));
    vo.setLeaveReason(participant.getLeaveReason());
    return vo;
  }
}
