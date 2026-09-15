package com.roomflow.domain.meeting;

import com.roomflow.common.enums.MeetingStatus;
import com.roomflow.common.util.BeijingTime;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class MeetingVO {

  private Long id;
  private String title;
  private String description;
  private Long roomId;
  private String roomName;
  private Long organizerId;
  private String organizerUsername;
  private OffsetDateTime startTime;
  private OffsetDateTime endTime;
  private MeetingStatus status;
  private Boolean endedEarly;
  private Integer participantCount;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public static MeetingVO from(
      Meeting meeting, String roomName, String organizerUsername, int participantCount) {
    MeetingVO vo = new MeetingVO();
    vo.setId(meeting.getId());
    vo.setTitle(meeting.getTitle());
    vo.setDescription(meeting.getDescription());
    vo.setRoomId(meeting.getRoomId());
    vo.setRoomName(roomName);
    vo.setOrganizerId(meeting.getOrganizerId());
    vo.setOrganizerUsername(organizerUsername);
    vo.setStartTime(BeijingTime.toOffset(meeting.getStartTime()));
    vo.setEndTime(BeijingTime.toOffset(meeting.getEndTime()));
    vo.setStatus(meeting.getStatus());
    vo.setEndedEarly(meeting.getEndedEarly());
    vo.setParticipantCount(participantCount);
    vo.setCreatedAt(BeijingTime.toOffset(meeting.getCreatedAt()));
    vo.setUpdatedAt(BeijingTime.toOffset(meeting.getUpdatedAt()));
    return vo;
  }
}
