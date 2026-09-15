package com.roomflow.domain.notification;

import com.roomflow.common.enums.NotificationType;
import com.roomflow.common.util.BeijingTime;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class NotificationVO {

  private Long id;
  private NotificationType type;
  private String title;
  private String content;
  private Long meetingId;
  private Boolean isRead;
  private OffsetDateTime createdAt;

  public static NotificationVO from(Notification notification) {
    NotificationVO vo = new NotificationVO();
    vo.setId(notification.getId());
    vo.setType(notification.getType());
    vo.setTitle(notification.getTitle());
    vo.setContent(notification.getContent());
    vo.setMeetingId(notification.getMeetingId());
    vo.setIsRead(notification.getIsRead());
    vo.setCreatedAt(BeijingTime.toOffset(notification.getCreatedAt()));
    return vo;
  }
}
