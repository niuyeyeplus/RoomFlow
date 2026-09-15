package com.roomflow.domain.meeting;

import com.roomflow.domain.participant.ParticipantVO;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MeetingDetailVO extends MeetingVO {

  /** All participant records including left/kicked, ordered by joinedAt asc. */
  private List<ParticipantVO> participants;

  public static MeetingDetailVO from(MeetingVO base, List<ParticipantVO> participants) {
    MeetingDetailVO vo = new MeetingDetailVO();
    vo.setId(base.getId());
    vo.setTitle(base.getTitle());
    vo.setDescription(base.getDescription());
    vo.setRoomId(base.getRoomId());
    vo.setRoomName(base.getRoomName());
    vo.setOrganizerId(base.getOrganizerId());
    vo.setOrganizerUsername(base.getOrganizerUsername());
    vo.setStartTime(base.getStartTime());
    vo.setEndTime(base.getEndTime());
    vo.setStatus(base.getStatus());
    vo.setEndedEarly(base.getEndedEarly());
    vo.setParticipantCount(base.getParticipantCount());
    vo.setCreatedAt(base.getCreatedAt());
    vo.setUpdatedAt(base.getUpdatedAt());
    vo.setParticipants(participants);
    return vo;
  }
}
