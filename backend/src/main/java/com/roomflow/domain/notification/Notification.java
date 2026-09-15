package com.roomflow.domain.notification;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.roomflow.common.enums.NotificationType;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("notification")
public class Notification {

  @TableId(type = IdType.AUTO)
  private Long id;

  /** Recipient account. */
  private Long accountId;

  private NotificationType type;
  private String title;
  private String content;
  private Long meetingId;
  private Boolean isRead;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;
}
